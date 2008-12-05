/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.sun.com/cddl/cddl.html or
 * install_dir/legal/LICENSE
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at install_dir/legal/LICENSE.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * $Id: Metrics.java,v 1.1 2008/09/10 18:25:54 akara Exp $
 *
 * Copyright 2005 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.faban.driver.engine;

import com.sun.faban.common.TextTable;
import com.sun.faban.driver.CustomMetrics;
import com.sun.faban.driver.CycleType;
import com.sun.faban.driver.RunControl;

import java.io.Serializable;
import java.util.Date;
import java.util.Formatter;
import java.util.logging.Logger;
import java.util.logging.Level;

/**
 * Generic statistics collection and reporting facility. For simple agents
 * without any additional statistics, this class can be used right away.
 * This class should also be extended to collect all additional statistics.
 *
 * @author Akara Sucharitakul
 */
public class Metrics implements Serializable, Cloneable {

	private static final long serialVersionUID = 1L;

    /*
    Response Histogram
    ~~~~~~~~~~~~~~~~~~
    Use fine and coarse bucket sizes for response time.
    Use fine buckets for up to 1.5 * Max90th
    Use coarse bucket sizes for 1.5 * Max90th to 5 * Max90th
    We do not care much about accuracy when we're far beyond the Max90th.
    We use 200 buckets up to Max90th. This means 300 buckets for up to
    1.5 * Max90th. Beyond that, we reduce the accuracy by 10x so only 70
    coarse buckets are needed. Altogether, we use 370 buckets, which is
    63% savings when compared to 1000 buckets. The logic will be slightly
    more complicated but by not much.
    */
    public static final int RESPBUCKET_SIZE_RATIO = 10;
    public static final int COARSE_RESPBUCKETS = 70; // Percentage coarse.
    public static final int FINE_RESPBUCKETS = (100 - COARSE_RESPBUCKETS) *
                                                    RESPBUCKET_SIZE_RATIO;
    public static final int RESPBUCKETS = FINE_RESPBUCKETS + COARSE_RESPBUCKETS;

    /** Number of delay time buckets in histogram. */
    public static final int DELAYBUCKETS = 100;

    // We use double here to prevent cumulative errors
    protected long fineRespBucketSize;  // Size of the fine and coarse
    protected long coarseRespBucketSize; // response time buckets, in ns.
    protected long fineRespHistMax; // Max fine response time
    protected long coarseRespHistMax; // Max coarse response time
    protected long delayBucketSize; // Size of each delay time bucket, in ns
    protected long graphBucketSize;  // Size of each graph bucket, in ns
    protected int graphBuckets;     // Number of graph buckets

    int threadCnt = 0;		// Threads this stat object is representing

    /* Stats for all transaction types - the first dimension of the array
     * is always the operation id. This is the index into the operations
     * array of the mix. The second dimension, existent only for histograms
     * is the bucket.
     */

	/**
     * Number of successful transactions during steady state.
     * This is used for final reporting and in-flight reporting of averages.
     */
    protected int[] txCntStdy;

    /**
     * Number of successful transactions total.
     * This is used for in-flight reporting only.
     */
    protected int[] txCntTotal;

    /**
     * Number of failed transactions during steady state.
     * This is used for final reporting and in-flight reporting of averages.
     */
    protected int[] errCntStdy;

    /**
     * Number of failed transactions total.
     * This is used for in-flight reporting only.
     */
    protected int[] errCntTotal;

    /**
     * The mix ratio of the operation during steady state.
     */
    protected double[] mixRatio;

    /**
     * Number of transactions the delay time
     * was successfuly recorded. Note that some transactions
     * while failing may still have the delay time recorded.
     * Transactions that do not have the delay time recorded
     * are transactions that fail before the critical section.
     */
    protected int[] delayCntStdy;
    /**
     * Sum of response times during steady state.
     * This is used for final reporting and in-flight reporting of averages.
     */
    protected double[] respSumStdy;

    /**
     * Sun of response times total.
     * This is used for in-flight reporting only.
     */
    protected double[] respSumTotal;

    /** Max. response time. */
    protected long[] respMax;

    /** Sum of delay (cycle/think) times. */
    protected long[] delaySum;

    /** Targeted delay times. */
    protected long[] targetedDelaySum;

    /** Maximum delay times. */
    protected long[] delayMax;

    /** Minimum delay times. */
    protected long[] delayMin;

    /** Sum of cycle time (not think time) for little's law verification. */
    protected long cycleSum = 0;

    /** Sum of elapsed times. */
    protected double[] elapse;

    /** Response time histogram. */
    protected int[][] respHist;

    /** Histogram of actual delay times. */
    protected int[][] delayHist;

    /** Histogram of selected delay times. */
    protected int[][] targetedDelayHist;

    /** Start time as absolute time, in ms */
    protected long startTime;

    /** End time as ms offset from start time */
    protected long endTime;

    /** End time as nanosec time */
    protected transient long endTimeNanos;

    /**
     * The thruput graph. This is updated throughout the run, not only
     * in steady state. The graph accumulates tx count during the run. The
     * final results need to be divided by the graph bucket size.
     */
    protected int[][] thruputGraph;	/* Thruput graph */

    /**
     * Graph of accumulated response times over the course of the run.
     * This data need to be divided by the accumulated tx count for the
     * bucket to get the avg response time in that bucket.
     */
    protected long[][] respGraph;

    /** The attached custom metrics */
    protected CustomMetrics attachment = null;

    /**
     * The final resulting metric. This field is only populated after
     * printing the summary report
     */
    protected double metric;

    /* Convenience variables */
    protected int driverType;
    protected String driverName;
    protected int txTypes;
    protected String[] txNames;
    protected int stdyState;
    protected transient AgentThread thread;    
    
    /**
     * @param agent
     */
    public Metrics(AgentThread agent) {
        this.thread = agent;
        RunInfo runInfo = RunInfo.getInstance();
        driverType = agent.agent.driverType;
        RunInfo.DriverConfig driverConfig = runInfo.driverConfig;
        driverName = driverConfig.name;

        txTypes = driverConfig.operations.length;

        stdyState = runInfo.stdyState;

        // We cannot serialize the agent itself but we only need the names.
        txNames = new String[txTypes];
        for (int i = 0; i < driverConfig.operations.length; i++) {
			txNames[i] = driverConfig.operations[i].name;
		}

        // Initialize all the arrays.
        txCntStdy = new int[txTypes];
        txCntTotal = new int[txTypes];
        errCntStdy = new int[txTypes];
        errCntTotal = new int[txTypes];
        delayCntStdy = new int[txTypes];
        respSumStdy = new double[txTypes];
        respSumTotal = new double[txTypes];
        respMax = new long[txTypes];
        delaySum = new long[txTypes];
        delayMax = new long[txTypes];
        delayMin = new long[txTypes];
        for (int i = 0; i < delayMin.length; i++) {
			delayMin[i] = Integer.MAX_VALUE; // init to the largest number
		}
        targetedDelaySum = new long[txTypes];
        elapse = new double[txTypes];
        respHist = new int[txTypes][RESPBUCKETS];
        delayHist = new int[txTypes][DELAYBUCKETS];
        targetedDelayHist = new int[txTypes][DELAYBUCKETS];

        // The actual run configuration is used in case it represents time.
        // This prevents us from over-allocating the thruput histogram.
        if (driverConfig.runControl == RunControl.TIME) {
			graphBuckets = 1 + (runInfo.rampUp + runInfo.stdyState +
                    runInfo.rampDown) / driverConfig.graphInterval;
		} else {
			graphBuckets = (int) Math.ceil(3600d * // Convert hr => s
                    runInfo.maxRunTime / driverConfig.graphInterval);
		}

        // Convert to ns.
        graphBucketSize = driverConfig.graphInterval * 1000000000l;
        thruputGraph = new int[txTypes][graphBuckets];
        respGraph = new long[txTypes][graphBuckets];

        // Find the maximum 90th% resp among all ops, in seconds
        double max90th = driverConfig.operations[0].max90th;
        for (int i = 1; i < txTypes; i++) {
			if (driverConfig.operations[i].max90th > max90th) {
				max90th = driverConfig.operations[i].max90th;
			}
		}

        // Calculate the response time histograms.
        double precision = driverConfig.responseTimeUnit.toNanos(1l);
        long max90nanos = Math.round(max90th * precision);
        fineRespBucketSize = max90nanos / 200l;  // 20% of scale of 1000
        fineRespHistMax = fineRespBucketSize * FINE_RESPBUCKETS;
        coarseRespBucketSize = fineRespBucketSize * RESPBUCKET_SIZE_RATIO;
        coarseRespHistMax = coarseRespBucketSize * COARSE_RESPBUCKETS +
                                                    fineRespHistMax;

        double delayHistMax = driverConfig.operations[0].
                cycle.getHistogramMax();

        // Find the max delay time histogram among ops, in ns
        for (int i = 1; i < txTypes; i++) {
            double opMaxDelay = driverConfig.operations[i].
                    cycle.getHistogramMax();
            if (opMaxDelay > delayHistMax) {
				delayHistMax = opMaxDelay;
			}
        }
        delayBucketSize = (int) Math.ceil(delayHistMax / DELAYBUCKETS);
    }

    /**
     * Updates the various stats for a successful transaction.
     */
    public void recordTx() {

        if (threadCnt == 0) {
            threadCnt = 1;
		}

        int txType = thread.currentOperation;
        DriverContext.TimingInfo timingInfo =
                thread.driverContext.timingInfo;
        endTimeNanos = timingInfo.respondTime;
        long responseTime = endTimeNanos - timingInfo.invokeTime -
                           timingInfo.pauseTime;
        if (responseTime < 0) {
            thread.logger.warning(thread.name +
                    ":Pause time too large - invoke : " +
                    timingInfo.invokeTime + ", respond : " + endTimeNanos +
                    ", pause : " + timingInfo.pauseTime);
            responseTime = 0; // Set it to 0 in this case so it does not
                              // destroy the whole run.
        }

        long elapsedTime = Long.MIN_VALUE;
        if (thread.agent.startTime != Long.MIN_VALUE)
            elapsedTime = endTimeNanos - thread.agent.startTime;

        if(elapsedTime > 0l) {
            if ((elapsedTime / graphBucketSize) >= graphBuckets) {
                thruputGraph[txType][graphBuckets - 1]++;
                respGraph[txType][graphBuckets - 1] += responseTime;
            } else {
                int bucket = (int) (elapsedTime / graphBucketSize);
                thruputGraph[txType][bucket]++;
                respGraph[txType][bucket] += responseTime;
            }
        }

        txCntTotal[txType]++;
        respSumTotal[txType] += responseTime;

        if (!thread.inRamp) {
            txCntStdy[txType]++;
            respSumStdy[txType] += responseTime;

            // post in histogram of response times
            int bucket;
            if (responseTime < fineRespHistMax) {
                bucket = (int) (responseTime / fineRespBucketSize);
            } else if (responseTime < coarseRespHistMax) {
                bucket = (int) (((responseTime - fineRespHistMax) /
                        coarseRespBucketSize) + FINE_RESPBUCKETS);
            } else {
                bucket = RESPBUCKETS - 1;
            }
            respHist[txType][bucket]++;

            if (responseTime > respMax[txType]) {
				respMax[txType] = responseTime;
			}
        }
    }

    /**
     * Records the error count for an unsuccessful transaction.
     */
    public void recordError() {

        if (threadCnt == 0) {
            threadCnt = 1;
		}

        int txType = thread.currentOperation;

        errCntTotal[txType]++;

        if (!thread.inRamp) {
            errCntStdy[txType]++;
		}

        if (thread.driverContext.timingInfo.respondTime !=
                AgentThread.TIME_NOT_SET)
            endTimeNanos = thread.driverContext.timingInfo.respondTime;
    }

    /**
     * Records the delay (think/cycle) time. The delay time is recorded
     * regardless of whether a transaction succeeds or fails.
     */
    public void recordDelayTime() {

        int txType = thread.previousOperation[thread.mixId];
        if (txType < 0) {// First cycle, previous op is not there. Don't record.
            return;
		}

        DriverContext.TimingInfo timingInfo =
                thread.driverContext.timingInfo;

        long actualDelayTime = -1l;
        long actualCycleTime = -1l;

        if (thread.isSteadyState(thread.startTime[thread.mixId],
                                 timingInfo.invokeTime)) {
            actualCycleTime = timingInfo.invokeTime -
                              thread.startTime[thread.mixId];
		}

        CycleType cycleType = RunInfo.getInstance().driverConfig.
                operations[thread.currentOperation].cycle.cycleType;
        switch (cycleType) {
            case CYCLETIME :
                actualDelayTime = actualCycleTime; break;
            case THINKTIME :
                if (thread.endTime[thread.mixId] >= 0) {// Normal
                    if (thread.isSteadyState(thread.endTime[thread.mixId],
                                             timingInfo.invokeTime)) {
                        actualDelayTime = timingInfo.invokeTime -
                                thread.endTime[thread.mixId];
					}
                } else { // Exceptions occurred, no respond time available
                    actualDelayTime = actualCycleTime;
                }
        }

        if (thread.mixId == 0 && actualCycleTime >= 0) {
        // cycleSum is for little's law verification.
        // We do not count background cycles to the cycleSum or the
        // verification will be totally off.
            cycleSum += actualCycleTime;
		}

        if (actualDelayTime < 0) {
            return;
		}

        ++delayCntStdy[txType];
        delaySum[txType] += actualDelayTime;
        targetedDelaySum[txType] += thread.delayTime[thread.mixId];


        if (actualDelayTime > delayMax[txType]) {
            delayMax[txType] = actualDelayTime;
		}
        if (actualDelayTime < delayMin[txType]) {
            delayMin[txType] = actualDelayTime;
		}

        int bucket = (int) (actualDelayTime / delayBucketSize);
        if (bucket >= DELAYBUCKETS) {
            delayHist[txType][DELAYBUCKETS - 1]++;
		} else {
            delayHist[txType][bucket]++;
		}
        bucket = (int) (thread.delayTime[thread.mixId] / delayBucketSize);
        if (bucket >= DELAYBUCKETS) {
            targetedDelayHist[txType][DELAYBUCKETS - 1]++;
		} else {
            targetedDelayHist[txType][bucket]++;
        }
    }

    /**
     * Wraps up the metric for serialization/transportation and/or
     * further processing.
     */
    public void wrap() {
        endTime = (endTimeNanos - thread.agent.startTime) / 1000000l;
    }

    /**
     * This method aggregates the stats with the stats of another thread.
     * It is called repeatedly, and the called passes it the stats of a
     * different thread, each time
     * @param s stats of next thread to be aggregated
     */
	public void add(Metrics s) {
        // Add up the thread count
		threadCnt += s.threadCnt;

        Logger logger = Logger.getLogger(getClass().getName());
        logger.finest("Adding cycleSum " + cycleSum + " and " + s.cycleSum);

        cycleSum += s.cycleSum;
        // Standard statistics
		for (int i = 0; i < txTypes; i++) {
			txCntStdy[i] += s.txCntStdy[i];
            txCntTotal[i] += s.txCntTotal[i];
            errCntStdy[i] += s.errCntStdy[i];
            errCntTotal[i] += s.errCntTotal[i];
            delayCntStdy[i] += s.delayCntStdy[i];
			respSumStdy[i] += s.respSumStdy[i];
            respSumTotal[i] += s.respSumTotal[i];
			delaySum[i] += s.delaySum[i];
			targetedDelaySum[i] += s.targetedDelaySum[i];
			if (s.respMax[i] > respMax[i]) {
				respMax[i] = s.respMax[i];
			}
			if (s.delayMax[i] > delayMax[i]) {
				delayMax[i] = s.delayMax[i];
			}
			if (s.delayMin[i] < delayMin[i]) {
				delayMin[i] = s.delayMin[i];
			}

			// sum up histogram buckets
			for (int j = 0; j < RESPBUCKETS; j++) {
				respHist[i][j] += s.respHist[i][j];
			}
			for (int j = 0; j < graphBuckets; j++) {
				thruputGraph[i][j] += s.thruputGraph[i][j];
                respGraph[i][j] += s.respGraph[i][j];
            }
			for (int j = 0; j < DELAYBUCKETS; j++) {
				delayHist[i][j] += s.delayHist[i][j];
			}
			for (int j = 0; j < DELAYBUCKETS; j++) {
				targetedDelayHist[i][j] += s.targetedDelayHist[i][j];
            }
        }

        if (s.startTime < startTime) {
            startTime = s.startTime;
		}

        // We want the last end time.
        if (s.endTime > endTime) {
            endTime = s.endTime;
		}

        if (attachment != null && s.attachment != null) {
			attachment.add(s.attachment);
		}
	}

    /**
     * @see java.lang.Object#clone()
     */
    @Override
    public Object clone() {
        Metrics clone = null;
        try {
            clone = (Metrics) super.clone();
            clone.txCntStdy = txCntStdy.clone();
            clone.txCntTotal = txCntTotal.clone();
            clone.errCntStdy = errCntStdy.clone();
            clone.errCntTotal = errCntTotal.clone();
            clone.delayCntStdy = delayCntStdy.clone();
            clone.respSumStdy = respSumStdy.clone();
            clone.respSumTotal = respSumTotal.clone();
            clone.respMax = respMax.clone();
            clone.delaySum = delaySum.clone();
            clone.targetedDelaySum = targetedDelaySum.clone();
            clone.delayMax = delayMax.clone();
            clone.delayMin = delayMin.clone();
            clone.respHist = new int[respHist.length][];
            for (int i = 0; i < respHist.length; i++) {
                clone.respHist[i] = respHist[i].clone();
			}
            clone.delayHist = new int[delayHist.length][];
            for (int i = 0; i < delayHist.length; i++) {
                clone.delayHist[i] = delayHist[i].clone();
			}
            clone.targetedDelayHist = new int[targetedDelayHist.length][];
            for (int i = 0; i < targetedDelayHist.length; i++) {
                clone.targetedDelayHist[i] = targetedDelayHist[i].clone();
			}
            clone.thruputGraph = new int[thruputGraph.length][];
            clone.respGraph = new long[respGraph.length][];
            for (int i = 0; i < thruputGraph.length; i++) {
                clone.thruputGraph[i] = thruputGraph[i].clone();
                clone.respGraph[i] = respGraph[i].clone();
            }
            if (attachment != null) {
                clone.attachment = (CustomMetrics) attachment.clone();
			}

        } catch (CloneNotSupportedException e) {
            // This should not happen as we already implement cloneable.
        }
        return clone;
    }

    /**
     * Calculates the aggregate TPS from the current stats.
     * @return The current aggregate TPS
     */
    public double getTps() {
        int totalCnt = 0;
        for (int i = 0; i < txTypes; i++) {
            totalCnt += txCntStdy[i];
		}
        return totalCnt * 1000d / stdyState;
    }

    /**
     * Provides a string presentation of the current stats.
     * @return The string representing the statistics.
     */
    @Override
    public String toString() {
        StringBuilder buffer = new StringBuilder();

        buffer.append("sumusers=" + threadCnt);
        buffer.append("\nruntime=" + stdyState);

        for (int i = 0; i < txTypes; i++) {
            buffer.append("\nsum" + txNames[i] + "Count=" + txCntStdy[i]);
            buffer.append("\nsum" + txNames[i] + "Resp=" + respSumStdy[i]);
            buffer.append("\nmax" + txNames[i] + "Resp=" + respMax[i]);
            buffer.append("\nsum" + txNames[i] + "Delay=" + delaySum[i]);
            buffer.append("\nmax" + txNames[i] + "Delay=" + delayMax[i]);
            buffer.append("\nmin" + txNames[i] + "Delay=" + delayMin[i]);
            buffer.append('\n');
        }

        buffer.append("Total cycle time = " + cycleSum);

        /* Now print out the histogram data */
        for (int i = 0; i < txTypes; i++) {
            buffer.append(txNames[i] + " Response Times Histogram\n");
            for (int j = 0; j < RESPBUCKETS; j++) {
                buffer.append(" " + respHist[i][j]);
			}
            buffer.append('\n');
            buffer.append(txNames[i] + " Throughput Graph\n");
            for (int j = 0; j < graphBuckets; j++) {
                buffer.append(" " + thruputGraph[i][j]);
			}
            buffer.append('\n');
            buffer.append(txNames[i] + " Response Time Graph\n");
            for (int j = 0; j < graphBuckets; j++) {
                buffer.append(" " + respGraph[i][j]);
			}
            buffer.append('\n');
            buffer.append(txNames[i] + " Cycle Times Histogram\n");
            for (int j = 0; j < DELAYBUCKETS; j++) {
                buffer.append(" " + delayHist[i][j]);
			}
            buffer.append('\n');
        }
        return(buffer.toString());
    }

    /**
     * Prints the summary report for the statistics. This will
     * usually be called once the statistics have been accumulated.
     *
     * @param buffer The buffer for outputting the summary
     * @param benchDef The benchmark definition
     * @return true if this driver passed, false if not
     */
    @SuppressWarnings("boxing")
    public boolean printSummary(StringBuilder buffer,
                                BenchmarkDefinition benchDef) {
        int metricTxCnt = 0;
        int sumTxCnt = 0;
        int sumFgTxCnt = 0;
        mixRatio = new double[txTypes];
        boolean success = true;
        double avg, tavg;
        long resp90;
        int sumtx, cnt90;
        RunInfo runInfo = RunInfo.getInstance();
        Formatter formatter = new Formatter(buffer);

        BenchmarkDefinition.Driver driver;
        if (benchDef.configPrecedence) {
            driver = runInfo.driverConfigs[driverType];
        } else {
            driver = benchDef.drivers[driverType];
        }

        int fgTxTypes = driver.mix[0].operations.length;

        space(4, buffer).append("<driverSummary name=\"").append(driverName).
                append("\">\n");

        for (int i = 0; i < txTypes; i++) {
            sumTxCnt += txCntStdy[i];
            if (driver.operations[i].countToMetric)
                metricTxCnt += txCntStdy[i];
		}

        for (int i = 0; i < fgTxTypes; i++) {
            sumFgTxCnt += txCntStdy[i];
		}

        int sumBgTxCnt = sumTxCnt - sumFgTxCnt;

        metric = metricTxCnt / (double) runInfo.stdyState;
        if (sumFgTxCnt > 0) {
            for (int i = 0; i < fgTxTypes; i++) {
				mixRatio[i] = txCntStdy[i] / (double) sumFgTxCnt;
			}
        }
        if (sumBgTxCnt > 0) {
            for (int i = fgTxTypes; i < txTypes; i++) {
				mixRatio[i] = txCntStdy[i] / (double) sumBgTxCnt;
			}
        }
        space(8, buffer);
        formatter.format("<metric unit=\"%s\">%.03f</metric>\n", driver.metric,
                metric);
        space(8, buffer).append("<startTime>").append(new Date(startTime)).
                append("</startTime>\n");
        space(8, buffer).append("<endTime>").append(new Date(startTime +
                endTime)).append("</endTime>\n");
        space(8, buffer).append("<totalOps unit=\"").append(driver.opsUnit).
                append("\">").append(sumTxCnt).append("</totalOps>\n");
        space(8, buffer).append("<users>").append(threadCnt).
                append("</users>\n");

        /* avg.rt = cycle time = tx. rt + cycle time */
        space(8, buffer);
        formatter.format("<rtXtps>%.04f</rtXtps>\n",
                cycleSum / (runInfo.stdyState * 1e9d));

        space(8, buffer).append("<passed>");
        int passStrOffset = buffer.length();
        buffer.append("true</passed>\n"); // We first assume passed
        // and will come correct it later if this is false;

        FlatMix[] mix;
        if (txTypes > fgTxTypes) {
            mix = new FlatMix[2];
            mix[1] = driver.mix[1].flatMix();
        } else {
            mix = new FlatMix[1];
        }
            
        mix[0] = driver.mix[0].flatMix();
        
        space(8, buffer);
        formatter.format(
                "<mix allowedDeviation=\"%.04f\">\n", mix[0].deviation / 100d);
        for (int i = 0; i < txNames.length; i++) {
            String nameModifier = "";
            double targetMix, targetDev;
            if (i < fgTxTypes) {
                targetMix = mix[0].mix[i];
                targetDev = mix[0].deviation;
            } else  { // Check that bg mix exists
                targetMix = mix[1].mix[i - fgTxTypes];
                targetDev = mix[1].deviation;
                nameModifier = " &amp;";
            }
            space(12, buffer).append("<operation name=\"").append(txNames[i]).
                    append(nameModifier).append("\">\n");
            space(16, buffer).append("<successes>").append(txCntStdy[i]).
                    append("</successes>\n");
            space(16, buffer).append("<failures>").append(errCntStdy[i]).
                    append("</failures>\n");
            space(16, buffer);
            formatter.format("<mix>%.04f</mix>\n", mixRatio[i]);
            space(16, buffer);
            
            formatter.format("<requiredMix>%.04f</requiredMix>\n", targetMix);
            boolean passed = true;
            double deviation = 100d * Math.abs(mixRatio[i] - targetMix);
            if (deviation > targetDev) {
                passed = false;
                success = false;
            }
            space(16, buffer).append("<passed>").append(passed).
                    append("</passed>\n");
            space(12, buffer).append("</operation>\n");
        }
        space(8, buffer).append("</mix>\n");

        // The precision of the response time, in nanosecs.
        // If sec, pecision is 1E9 nanos,
        // if microsec, precision is 1E3 nanos, etc.
        double precision = driver.responseTimeUnit.toNanos(1l);
        String responseTimeUnit = driver.responseTimeUnit.toString().
                toLowerCase();

        space(8, buffer).append("<responseTimes unit=\"").
                append(responseTimeUnit).append("\">\n");
        for (int i = 0; i < txNames.length; i++) {
            String nameModifier;
            if (i < fgTxTypes) {
                nameModifier = "";
            } else {
                nameModifier = " &amp;";
            }
            double max90 = driver.operations[i].max90th;
            long max90nanos = Math.round(max90 * precision);

            space(12, buffer);
            formatter.format("<operation name=\"%s%s\" r90th=\"%5.3f\">\n",
                    txNames[i], nameModifier, max90);
            if (txCntStdy[i] > 0) {
                boolean pass90 = true;
                space(16, buffer);
                formatter.format("<avg>%5.3f</avg>\n",
                        (respSumStdy[i]/txCntStdy[i]) / precision);
                space(16, buffer);
                formatter.format("<max>%5.3f</max>\n", respMax[i] / precision);
                sumtx = 0;
                cnt90 = (int)(txCntStdy[i] * .90d);
                int j = 0;
                for (; j < respHist[i].length; j++) {
                    sumtx += respHist[i][j];
                    if (sumtx >= cnt90)	{	/* 90% of tx. got */
                        break;
                    }
                }
                // We report the base of the next bucket.
                ++j;
                if (j < FINE_RESPBUCKETS)
                    resp90 = j * fineRespBucketSize;
                else if (j < RESPBUCKETS)
                    resp90 = (j - FINE_RESPBUCKETS) * coarseRespBucketSize +
                            fineRespHistMax;
                else
                    resp90 = coarseRespHistMax;

                space(16, buffer);
                formatter.format("<p90th>%5.3f</p90th>\n", resp90 / precision);
                if (resp90 > max90nanos) {
                    pass90 = false;
                    success = false;
                }
                space(16, buffer).append("<passed>").append(pass90).
                        append("</passed>\n");
            } else {
                space(16, buffer).append("<avg/>\n");
                space(16, buffer).append("<max/>\n");
                space(16, buffer).append("<p90th/>\n");
                space(16, buffer).append("<passed/>\n");
            }
            space(12, buffer).append("</operation>\n");
        }
        space(8, buffer).append("</responseTimes>\n");

        space(8, buffer).append("<delayTimes>\n");
        for (int i = 0; i < txNames.length; i++) {
            
            String nameModifier;
            if (i < fgTxTypes) {
                nameModifier = "";
            } else {
                nameModifier = " &amp;";
            }

            String typeString = null;
            switch (driver.operations[i].cycle.cycleType) {
                case CYCLETIME: typeString = "cycleTime"; break;
                case THINKTIME: typeString = "thinkTime";
            }
            space(12, buffer).append("<operation name=\"").append(txNames[i]).
                    append(nameModifier).append("\" type=\"").
                    append(typeString).append("\">\n");
            if (delayCntStdy[i] > 0) {
                avg = delaySum[i] / (delayCntStdy[i] * 1e9d);
                tavg =  targetedDelaySum[i] / (delayCntStdy[i] * 1e9d);
                space(16, buffer);
                formatter.format("<targetedAvg>%.3f</targetedAvg>\n",tavg);
                space(16, buffer);
                formatter.format("<actualAvg>%.3f</actualAvg>\n", avg);
                space(16, buffer);
                formatter.format("<min>%.3f</min>\n", delayMin[i]/1e9d);
                space(16, buffer);
                formatter.format("<max>%.3f</max>\n", delayMax[i]/1e9d);

                boolean passDelay = true;

                // Make sure we're not dealing with the 0 think time case.
                // We cannot check a deviation on 0 think time.
                if (driver.operations[i].cycle.cycleType == CycleType.CYCLETIME
                        || tavg > 0.001d) {
                    passDelay = (Math.abs(avg - tavg)/tavg <=
                            driver.operations[i].cycle.cycleDeviation /100d);
				}

                space(16, buffer);
                buffer.append("<passed>").append(passDelay).
                        append("</passed>\n");
                if (!passDelay) {
                    success = false;
				}
            } else {
                space(16, buffer).append("<targetedAvg/>\n");
                space(16, buffer).append("<actualAvg/>\n");
                space(16, buffer).append("<min/>\n");
                space(16, buffer).append("<max/>\n");
                space(16, buffer).append("<passed/>\n");
            }
            space(12, buffer).append("</operation>\n");
        }
        space(8, buffer).append("</delayTimes>\n");

        if (attachment != null) {
            Logger logger = Logger.getLogger(
                                        this.getClass().getName());
            Result.init(this); // Creates the result for the attachment to use.
            CustomMetrics.Element[] elements = null;
            try {
                elements = attachment.getResults();
            } catch (Exception e) { // Ensure the getResults
                                    // doesn't break report generation.
                logger.log(Level.WARNING, "Exceptione reporting CustomMetrics",
                                                                            e);
                elements = null;
            }
            if (elements != null && elements.length > 0) {
                space(8, buffer).append("<miscStats>\n");
                for (CustomMetrics.Element element: elements) {
                    if (element == null) {
                        logger.warning("Null element returned from " +
                                attachment.getClass().getName() +
                                ".getResults, ignored!");
                        continue;
                    }
                    space(12, buffer).append("<stat>\n");
                    if (element.description != null) {
                        space(16, buffer).append("<description>").append(
                                element.description).append("</description>\n");
                    } else {
                        space(16, buffer).append("<description/>\n");
                    }
                    if (element.result != null) {
                        space(16, buffer).append("<result>").
                                append(element.result).append("</result>\n");
                    } else {
                        space(16, buffer).append("<result/>\n");
                    }
                    if (element.target != null) {
                        space(16, buffer).append("<target>").append(
                                element.target).append("</target>\n");
                    }
                    if (element.allowedDeviation != null) {
                        space(16, buffer).append("<allowedDeviation>").
                                append(element.allowedDeviation).
                                append("</allowedDeviation>\n");
                    }
                    if (element.passed != null) {
                        space(16, buffer).append("<passed>").append(element.
                                passed.booleanValue()).append("</passed>\n");
                        if (!element.passed.booleanValue())
                            success = false;
                    }
                    space(12, buffer).append("</stat>\n");
                }
                space(8, buffer).append("</miscStats>\n");
            }
        }

        space(4, buffer).append("</driverSummary>\n");

        // Go back and correct the driver-level pass/fail if not success
        if (!success) {
            buffer.replace(passStrOffset, passStrOffset + "true".length(),
                    "false");
		}

        return success;
    }

    /**
     * Scans the data for the upper limit of used buckets.
     * @param data The data, histogram or graph
     * @return The index of the first unused bucket
     */
    private int getBucketLimit(int[][] data) {
        int maxBucketId = data[0].length - 1;

        bucketScanLoop:
        while (maxBucketId >= 0) {
			for (int i = 0; i < data.length; i++) {
				if (data[i][maxBucketId] != 0) {
                    break bucketScanLoop;
				}
			}
            --maxBucketId;
		}
        ++maxBucketId;
        if (maxBucketId < data[0].length) {
            ++maxBucketId; // Include one row of zeros if not last row.
		}
        return maxBucketId;
    }

    /**
     * Scans the data for the upper limit of used buckets.
     * @param data The data, histogram or graph
     * @return The index of the first unused bucket
     */
    private int getBucketLimit(long[][] data) {
        int maxBucketId = data[0].length - 1;

        bucketScanLoop:
        for (; maxBucketId >= 0; maxBucketId--) {
			for (int i = 0; i < data.length; i++) {
				if (data[i][maxBucketId] != 0) {
                    break bucketScanLoop;
				}
			}
		}
        ++maxBucketId;
        if (maxBucketId < data[0].length) {
            ++maxBucketId; // Include one row of zeros if not last row.
		}
        return maxBucketId;
    }

    /**
     * The respHist, or response histogram has a special structure:
     * The lower buckets are fine-grained buckets. The higher buckets
     * are coarse-grained bucket covering 10x as much time. It is done this
     * way to save memory (from 1000 entries per thread per operation, down to
     * 370 entries). For low response times, we care a lot about the exact
     * response time and therefore we use fine-grained buckets. For large
     * response times we just want to know the ballpark, but not the exact
     * number. So it does not make sense to keep the same bucket size
     * throughout. We use fine granularity below 1.5x largest set 90th% and
     * coarse granularity for anything beyond that.
     *
     * Now, we need to flatten the response time histogram into a flat one
     * before plotting. We do this, here. We'll end up with more entries, but
     * we really don't care since this is one copy, once per run at report time.
     */
    private void flattenRespHist() {
        int limit = getBucketLimit(respHist);

        // If all buckets are used, the last one does not get extrapolated
        // as it has the data of that bucket and beyond.
        boolean spareLastBucket = false;
        if (limit == respHist[0].length)
            spareLastBucket = true;

        if (limit > FINE_RESPBUCKETS) {
            int size;
            if (spareLastBucket) {
                --limit;
                size = (COARSE_RESPBUCKETS - 1) * RESPBUCKET_SIZE_RATIO +
                                                        FINE_RESPBUCKETS + 1;
            } else {
                size = (limit - FINE_RESPBUCKETS) * RESPBUCKET_SIZE_RATIO +
                                                        FINE_RESPBUCKETS;
            }
            int[][] respHist = new int[txTypes][size];
            for (int i = 0; i < txTypes; i++) {

                // Copy the fine buckets unchanged.
                for (int j = 0; j < FINE_RESPBUCKETS; j++)
                    respHist[i][j] = this.respHist[i][j];

                for (int j = FINE_RESPBUCKETS; j < limit; j++) {
                    int count = this.respHist[i][j];
                    // Spread the count among all 10 flat buckets.
                    int base = count / RESPBUCKET_SIZE_RATIO;
                    int remainder = count % RESPBUCKET_SIZE_RATIO;
                    int baseIdx = (j - FINE_RESPBUCKETS) *
                                    RESPBUCKET_SIZE_RATIO + FINE_RESPBUCKETS;
                    int k = 9;
                    // The higher buckets get the base
                    for (; k >= remainder; k--)
                        respHist[i][baseIdx + k] = base;
                    // The lower remaining buckets get the base + 1
                    ++base;
                    for (; k >= 0; k--)
                        respHist[i][baseIdx + k] = base;
                }
                if (spareLastBucket)
                    // Just copy the last bucket.
                    respHist[i][size - 1] = this.respHist[i][limit];
            }
            this.respHist = respHist;
        }
    }

    /**
     * @param b
     */
    public void printDetail(StringBuilder b)  {
        RunInfo runInfo = RunInfo.getInstance();
        BenchmarkDefinition.Driver driver = runInfo.driverConfigs[driverType];
        double precision = driver.responseTimeUnit.toNanos(1l);
        double graphBucketSize = this.graphBucketSize / 1e9d;
        String responseTimeUnit = driver.responseTimeUnit.toString().
                toLowerCase();

        flattenRespHist();

        printGraph(b, "Throughput", graphBucketSize,
                "%.0f", "%.2f", thruputGraph, graphBucketSize);

        printGraph(b, "Response Times (" + responseTimeUnit +
                ")", graphBucketSize, "%.0f", "%.6f", respGraph,
                thruputGraph, precision);

        printHistogram(b, "Frequency Distribution of Response Times (" +
                responseTimeUnit + ")", fineRespBucketSize / precision, "%.5f",
                respHist);

        printHistogram(b, "Frequency Distribution of Cycle/Think Times " +
                "(seconds)", delayBucketSize / 1e9d, "%.3f", delayHist);

        printHistogram(b, "Frequency Distribution of Targeted Cycle/Think " +
                "Times (seconds)", delayBucketSize / 1e9d, "%.3f",
                targetedDelayHist);
    }

    @SuppressWarnings("boxing")
    private void printGraph(StringBuilder b, String label, double unit,
                            String unitFormat, String dataFormat,
                            int[][] rawGraph, double divider) {

        int bucketLimit = rawGraph[0].length;

        // Check the histogram and do not output unused buckets if needed.
        // The graph buckets are sized according to the run time.
        // So we'll scan only if the run is cycleControl.
        if (RunInfo.getInstance().driverConfigs[driverType].
                runControl == RunControl.CYCLES) {
            bucketLimit = getBucketLimit(rawGraph);
		}

        // Data header
        b.append("Section: ").append(driverName).append(' ').append(label).
                append('\n');
        b.append("Display: Line\n");

        TextTable table = new TextTable(bucketLimit, txTypes + 1);

        // The X axis headers and column headers, or legends
        table.setHeader(0, "Time (s)");
        for (int j = 0; j < txTypes; j++) {
            table.setHeader(j + 1, txNames[j]);
		}

        // The X axis and the data
        for (int i = 0; i < bucketLimit; i++) {
            // The X axis
            table.setField(i, 0, String.format(unitFormat, unit * i));

            // The data
            for (int j = 0; j < txTypes; j++) {
                table.setField(i, j + 1,
                        String.format(dataFormat, rawGraph[j][i]/divider));
            }
        }
        table.format(b);
        b.append('\n');
    }

    @SuppressWarnings("boxing")
    private void printGraph(StringBuilder b, String label, double unit,
                            String unitFormat, String dataFormat,
                            long[][] rawGraph, int[][] divider, double divider2){

        int bucketLimit = rawGraph[0].length;

        // Check the histogram and do not output unused buckets if needed.
        // The graph buckets are sized according to the run time.
        // So we'll scan only if the run is cycleControl.
        if (RunInfo.getInstance().driverConfigs[driverType].
                runControl == RunControl.CYCLES) {
            bucketLimit = getBucketLimit(rawGraph);
		}

        // Data header
        b.append("Section: ").append(driverName).append(' ').append(label).
                append('\n');
        b.append("Display: Line\n");

        TextTable table = new TextTable(bucketLimit, txTypes + 1);

        // The X axis headers and column headers, or legends
        table.setHeader(0, "Time (s)");
        for (int j = 0; j < txTypes; j++) {
            table.setHeader(j + 1, txNames[j]);
		}

        // The X axis and the data
        for (int i = 0; i < bucketLimit; i++) {
            // The X axis
            table.setField(i, 0, String.format(unitFormat, unit * i));

            // The data
            for (int j = 0; j < txTypes; j++) {
                double data = 0d;
                if (divider[j][i] != 0) {
                    data = rawGraph[j][i] / (divider2 * divider[j][i]);
				}
                table.setField(i, j + 1, String.format(dataFormat, data));
            }
        }
        table.format(b);
        b.append('\n');
    }

    @SuppressWarnings("boxing")
    private void printHistogram(StringBuilder b, String label, double unit,
                                String unitFormat, int[][] histogram) {

        // First, check the histogram and do not output unused buckets.
        int bucketLimit = getBucketLimit(histogram);

        // Data header
        b.append("Section: ").append(driverName).append(' ').append(label).
                append('\n');
        b.append("Display: Line\n");

        TextTable table = new TextTable(bucketLimit, txTypes + 1);

        // The X axis headers and column headers, or legends
        table.setHeader(0, "Time");
        for (int j = 0; j < txTypes; j++) {
            table.setHeader(j + 1, txNames[j]);
		}

        // The X axis and the data
        for (int i = 0; i < bucketLimit; i++) {
            // The X axis
            table.setField(i, 0, String.format(unitFormat, unit * i));

            // The data
            for (int j = 0; j < txTypes; j++) {
				table.setField(i, j + 1, String.valueOf(histogram[j][i]));
			}
        }
        table.format(b);
        b.append('\n');
    }

    static StringBuilder space(int space, StringBuilder buffer) {
        for (int i = 0; i < space; i++) {
            buffer.append(' ');
		}
        return buffer;
    }
}