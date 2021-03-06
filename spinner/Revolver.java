/**
 * Copyright 2014 Grafos.ml
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */ 
package ml.grafos.okapi.spinner; 
import it.unimi.dsi.fastutil.shorts.ShortArrayList; 
import java.io.DataInput; 
import java.io.DataOutput; 
import java.io.IOException; 
import java.math.BigDecimal; 
import java.util.Arrays; 
import java.util.Collections; 
import java.util.LinkedList; 
import java.util.Random; 
import java.util.regex.Pattern; 
import org.apache.giraph.aggregators.DoubleSumAggregator; 
import org.apache.giraph.aggregators.LongSumAggregator; 
import org.apache.giraph.edge.Edge; 
import org.apache.giraph.edge.EdgeFactory; 
import org.apache.giraph.graph.AbstractComputation; 
import org.apache.giraph.graph.Vertex; 
import org.apache.giraph.io.EdgeReader; 
import org.apache.giraph.io.formats.TextEdgeInputFormat; 
import org.apache.giraph.io.formats.TextVertexOutputFormat; 
import org.apache.giraph.io.formats.TextVertexValueInputFormat; 
import org.apache.giraph.master.DefaultMasterCompute; 
import org.apache.hadoop.conf.Configuration; 
import org.apache.hadoop.io.DoubleWritable; 
import org.apache.hadoop.io.LongWritable; 
import org.apache.hadoop.io.NullWritable; 
import org.apache.hadoop.io.Text; 
import org.apache.hadoop.io.Writable; 
import org.apache.hadoop.mapreduce.InputSplit; 
import org.apache.hadoop.mapreduce.TaskAttemptContext; 
import com.google.common.collect.Lists; 
import it.unimi.dsi.fastutil.doubles.DoubleArrayList;

import java.util.HashMap;
import java.util.Map;


/**
 * Implements the Spinner edge-based balanced k-way partitioning of a graph.
 *
 * The algorithm partitions N vertices across k partitions, trying to keep the
 * number of edges in each partition similar. The algorithm is also able to
 * adapt a previous partitioning to a set of updates to the graph, e.g. adding
 * or removing vertices and edges. The algorithm can also adapt a previous
 * partitioning to updates to the number of partitions, e.g. adding or removing
 * partitions.
 *
 * The algorithm works as follows:
 *
 * 1) The vertices are assigned to partitions according to the following
 * heuristics: a) If we are computing a new partitioning, assign the vertex to a
 * random partition b) If we are adapting the partitioning to graph changes: (i)
 * If the vertex was previously partitioned, assign the previous label (ii) If
 * the vertex is new, assign a random partition c) If we are adapting to changes
 * to the number of partitions: (i) If we are adding partitions: assign the
 * vertex to one of the new partitions (uniformly at random) with probability p
 * (see paper), (ii) If we are removing partitions: assign vertices belonging to
 * the removed partitions to the other partitions uniformly at random. After the
 * vertices are initialized, they communicate their label to their neighbors,
 * and update the partition loads according to their assignments.
 *
 * 2) Each vertex computes the score for each label based on loads and the
 * labels from incoming neighbors. If a new partition has higher score (or
 * depending on the heuristics used), the vertex decides to try to migrate
 * during the following superstep. Otherwise, it does nothing.
 *
 * 3) Interested vertices try to migrate according to the ratio of vertices who
 * want to migrate to a partition i and the remaining capacity of partition i.
 * Vertices who succeed in the migration update the partition loads and
 * communicate their migration to their neighbors true a message.
 *
 * 4) Point (2) and (3) keep on being alternating until convergence is reach,
 * hence till the global score of the partitioning does not change for a number
 * of times over a certain threshold.
 *
 * Each vertex stores the position of its neighbors in the edge values, to avoid
 * re-communicating labels at each iteration also for non-migrating vertices.
 *
 * Due to the random access to edges, this class performs much better when using
 * OpenHashMapEdges class provided with this code.
 *
 * To use the partitioning computed by this class in Giraph, see
 * {@link PrefixHashPartitionerFactor}, {@link PrefixHashWorkerPartitioner}, and
 * {@link PartitionedLongWritable}.
 *
 * @author claudio
 *
 */ 

public class Spinner {
	private static final String AGGREGATOR_LOAD_PREFIX = "AGG_LOAD_";
	private static final String AGGREGATOR_DEMAND_PREFIX = "AGG_DEMAND_";
	private static final String AGGREGATOR_STATE = "AGG_STATE";
	private static final String AGGREGATOR_MIGRATIONS = "AGG_MIGRATIONS";
	private static final String AGGREGATOR_LOCALS = "AGG_LOCALS";
	private static final String NUM_PARTITIONS = "spinner.numberOfPartitions";
	private static final int DEFAULT_NUM_PARTITIONS = 32;
	private static final String ADDITIONAL_CAPACITY = "spinner.additionalCapacity";
	private static final float DEFAULT_ADDITIONAL_CAPACITY = 0.05f;
	private static final String LAMBDA = "spinner.lambda";
	private static final float DEFAULT_LAMBDA = 1.0f;
	private static final String MAX_ITERATIONS = "spinner.maxIterations";
	private static final int DEFAULT_MAX_ITERATIONS = 290;
	private static final String CONVERGENCE_THRESHOLD = "spinner.threshold";
	private static final float DEFAULT_CONVERGENCE_THRESHOLD = 0.001f;
	private static final String EDGE_WEIGHT = "spinner.weight";
	private static final byte DEFAULT_EDGE_WEIGHT = 1;
	private static final String REPARTITION = "spinner.repartition";
	private static final short DEFAULT_REPARTITION = 0;
	private static final String WINDOW_SIZE = "spinner.windowSize";
	private static final int DEFAULT_WINDOW_SIZE = 5;
	private static final String COUNTER_GROUP = "Partitioning Counters";
	private static final String MIGRATIONS_COUNTER = "Migrations";
	private static final String ITERATIONS_COUNTER = "Iterations";
	private static final String PCT_LOCAL_EDGES_COUNTER = "Local edges (%)";
	private static final String MAXMIN_UNBALANCE_COUNTER = "Maxmin unbalance (x1000)";
	private static final String MAX_NORMALIZED_UNBALANCE_COUNTER = "Max normalized unbalance (x1000)";
	private static final String SCORE_COUNTER = "Score (x1000)";
    
    private static final String ALPHA = "spinner.alpha";
    private static final float DEFAULT_ALPHA = .98f;
    private static final String BETA = "spinner.beta";
    private static final float DEFAULT_BETA = 0.02f;
    private static final String AGGREGATOR_CUT = "cut edges ";  
    private static final String DIRECTED_EDGES = "directed edges ";  
    
    
	public static class ComputeNewPartition
			extends
			AbstractComputation<LongWritable, VertexValue, EdgeValue, PartitionMessage, PartitionMessage> {
		private ShortArrayList maxIndices = new ShortArrayList();
		private Random rnd = new Random();
		private String[] demandAggregatorNames;
		private int[] partitionFrequency;
		private long[] loads;
		private long totalCapacity;
		private short numberOfPartitions;
		private short repartition;
		private double additionalCapacity;
		private double lambda;
        private long numDirectedEdges;
        private long defaultCapacity;
        
		private double computeW(int newPartition) {
			return new BigDecimal(((double) loads[newPartition]) / totalCapacity).setScale(3, BigDecimal.ROUND_CEILING).doubleValue();
            //return ((double) loads[newPartition] / defaultCapacity);
		}
		/*
		 * Request migration to a new partition
		 */
		private void requestMigration(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				int numberOfEdges, short currentPartition, short newPartition) {
			vertex.getValue().setNewPartition(newPartition);
			aggregate(demandAggregatorNames[newPartition], new LongWritable(
					numberOfEdges));
			loads[newPartition] += numberOfEdges;
			loads[currentPartition] -= numberOfEdges;
		}
		/*
		 * Update the neighbor labels when they migrate
		 */
		private void updateNeighborsPartitions(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				Iterable<PartitionMessage> messages) {
			for (PartitionMessage message : messages) {
				LongWritable otherId = new LongWritable(message.getSourceId());
				EdgeValue oldValue = vertex.getEdgeValue(otherId);
				vertex.setEdgeValue(otherId, new EdgeValue(message.getPartition(), oldValue.getWeight()));
			}
		}
		/*
		 * Compute the occurrences of the labels in the neighborhood
		 */
		private int computeNeighborsLabels(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex) {
			Arrays.fill(partitionFrequency, 0);
			int totalLabels = 0;
			int localEdges = 0;
            int localCut = 0;
            short currentPartition = vertex.getValue().getCurrentPartition();
            HashMap<Long, Boolean> map = vertex.getValue().getDirectedEdges();
			for (Edge<LongWritable, EdgeValue> e : vertex.getEdges()) {
				//partitionFrequency[currentPartition] += e.getValue().getWeight();
                partitionFrequency[e.getValue().getPartition()] += e.getValue().getWeight();
				totalLabels += e.getValue().getWeight();
                if(map.get(e.getTargetVertexId().get())) {
                    //if (e.getValue().getPartition() == vertex.getValue().getCurrentPartition()) {
                    if (e.getValue().getPartition() == currentPartition) {
                        localEdges++;
                    } else {
                        localCut++;
                    }
                }
			}
			// update cut edges stats
			aggregate(AGGREGATOR_LOCALS, new LongWritable(localEdges));
            aggregate(AGGREGATOR_CUT, new LongWritable(localCut));
			return totalLabels;
		}
		/*
		 * Choose a random partition with preference to the current
		 */
		private short chooseRandomPartitionOrCurrent(short currentPartition) {
			short newPartition;
			if (maxIndices.size() == 1) {
				newPartition = maxIndices.get(0);
			} else {
				// break ties randomly unless current
				if (maxIndices.contains(currentPartition)) {
					newPartition = currentPartition;
				} else {
					newPartition = maxIndices
							.get(rnd.nextInt(maxIndices.size()));
				}
			}
			return newPartition;
		}
		/*
		 * Choose deterministically on the label with preference to the current
		 */
		private short chooseMinLabelPartition(short currentPartition) {
			short newPartition;
			if (maxIndices.size() == 1) {
				newPartition = maxIndices.get(0);
			} else {
				if (maxIndices.contains(currentPartition)) {
					newPartition = currentPartition;
				} else {
					newPartition = maxIndices.get(0);
				}
			}
			return newPartition;
		}
		/*
		 * Choose a random partition regardless
		 */
		private short chooseRandomPartition() {
			short newPartition;
			if (maxIndices.size() == 1) {
				newPartition = maxIndices.get(0);
			} else {
				newPartition = maxIndices.get(rnd.nextInt(maxIndices.size()));
			}
			return newPartition;
		}
		/*
		 * Compute the new partition according to the neighborhood labels and
		 * the partitions' loads
		 */
		private short computeNewPartition(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				int totalLabels) {
			short currentPartition = vertex.getValue().getCurrentPartition();
			short newPartition = -1;
			double bestState = -Double.MAX_VALUE;
			double currentState = 0;
            
            double[] arrLPA = new double[numberOfPartitions + repartition];
            double[] arrPF  = new double[numberOfPartitions + repartition];
            double[] arrW   = new double[numberOfPartitions + repartition];
            double[] arr    = new double[numberOfPartitions + repartition];
            double[] arr_    = new double[numberOfPartitions + repartition];
            boolean hasNegative = false;
            

			for (short i = 0; i < numberOfPartitions + repartition; i++) {
				// original LPA
				double LPA = ((double) partitionFrequency[i]) / totalLabels;
				// penalty function
				double PF = lambda * computeW(i);
				// compute the rank and make sure the result is > 0
				double H = lambda + LPA - PF;
                arr_[i] = H;
                // 2nd score
                
                arrLPA[i] = ((double) partitionFrequency[i]) / totalLabels;
                //arrPF[i] = lambda + additionalCapacity - ((double) loads[i]) / defaultCapacity;
                arrPF[i] = lambda - computeW(i);
                //arr[i] = arrLPA[i] + arrPF[i];
                if(arrPF[i] < 0) {
                    hasNegative = true;
                }
                
               //arr[i] = arrLPA[i] * ((double) 1/ arrPF[i]);
                //arr[i] = LPA * ((double) 1 / computeW(i));
			}
            /*
            if (vertex.getId().get() == 0) {
                System.out.print("     arrPF ");
                for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                	System.out.print(arrPF[i] + " ");
                }
                System.out.println();
            }
            */
            
            double sumPF = 0;
            double maxPF = -Double.MAX_VALUE;
            double minPF = +Double.MAX_VALUE;
            double rangePF = 0;            
            
            /*            
            for (short i = 0; i < numberOfPartitions + repartition; i++) {
                sumPF = sumPF + arr[i];
            }
            
            for (short i = 0; i < numberOfPartitions + repartition; i++) {
                arr[i] = arr[i] / sumPF;
            }
            
            for (short i = 0; i < numberOfPartitions + repartition; i++) {
                arr[i] =  arrLPA[i] + (arrLPA[i] * arrPF[i]);
            }
            
            */

            
            if(hasNegative) {
                for (short i = 0; i < numberOfPartitions + repartition; i++) {
                    if(arrPF[i] <  minPF) {
                        minPF = arrPF[i];
                    }
                    if(arrPF[i] >  maxPF) {
                        maxPF = arrPF[i];
                    }
                }
                rangePF = maxPF - minPF;
                for (short i = 0; i < numberOfPartitions + repartition; i++) {
                    arrPF[i] = (arrPF[i] - minPF) / rangePF;
                }
            }

            for (short i = 0; i < numberOfPartitions + repartition; i++) {
                sumPF = sumPF + arrPF[i];
            }
            
            for (short i = 0; i < numberOfPartitions + repartition; i++) {
                arrPF[i] = arrPF[i] / sumPF;
            }
            
            for (short i = 0; i < numberOfPartitions + repartition; i++) {
                arr[i] = (arrPF[i] + arrLPA[i]) / 2;  
            }
            
            short maxPartition = 0;
            maxIndices.clear();
 			for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                double H = arr[i];              
                if (i == currentPartition) {
					currentState = H;
				}
				if (H > bestState) {
					bestState = H;
					maxIndices.clear();
					maxIndices.add(i);
                    maxPartition = i;
				} else if (H == bestState) {
					maxIndices.add(i);
				}
            }
          
            
            LAProbability = vertex.getValue().getLAProbability();
            LASignal = vertex.getValue().getLASignal();
            //newPartition  = rouletteWheel(LAProbability);
            newPartition  = actionSelection(LAProbability);
            //newPartition = chooseRandomPartitionOrCurrent(currentPartition);
            PartitionMessage message = new PartitionMessage(vertex.getId().get(), maxPartition, 1); // Changed maxPartition to newPartition
            sendMessageToAllEdges(vertex, message);
            LASignal.set(maxPartition, 1);
            
            /*
            double w0 = 0.9;
            double w1 = 0.4;
            short numEvents = (short) LASignal.size();
            double w = (double) ((w0 - w1) * getSuperstep() * Math.sqrt(numEvents)) / maxIterations;
            LASignal.set(maxPartition, (double) w * partitionFrequency[maxPartition]);
            */
            //for (Edge<LongWritable, EdgeValue> e : vertex.getEdges()) {
             //  PartitionMessage message = new PartitionMessage(vertex.getId().get(), maxPartition, e.getValue().getWeight());
             //   sendMessage(e.getTargetVertexId(), message);
            //}

            if (vertex.getId().get() == 0) {
                System.out.println("Superstep(" + getSuperstep() + ") Vertex(" + vertex.getId().get() + ")");
                System.out.println("    newPartition: "     + newPartition + "  currentPartition: " + currentPartition + "   maxPartition: "     + maxPartition);

                System.out.print("    loads ");
                for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                	System.out.print(loads[i] + " ");
                }
                System.out.println();
                
                System.out.print("    computeW ");
                for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                	System.out.print(computeW(i) + " ");
                }
                System.out.println();
                
                System.out.print("    partitionFrequency ");
                for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                	System.out.print(partitionFrequency[i] + " ");
                }
                System.out.println();
                System.out.println("    totalCapacity: " + totalCapacity + " defaultCapacity: " + defaultCapacity + " additionalCapacity: " + additionalCapacity);
                
                //System.out.println("    hasNegative " + hasNegative +  " minPF " + minPF + " maxPF " + maxPF + " rangePF " + rangePF + " sumPF " + sumPF);
                
                System.out.print("    arrPF ");
                for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                	System.out.print(arrPF[i] + " ");
                }
                System.out.println();
                
                System.out.print("    arrLPA ");
                for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                	System.out.print(arrLPA[i] + " ");
                }
                System.out.println();
                
                System.out.print("    scores ");
                for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                	System.out.print(arr[i] + " ");
                }
                System.out.println();
                System.out.print("    scores_ ");
                for (short i = 0; i < numberOfPartitions + repartition; i++) {  
                	System.out.print(arr_[i] + " ");
                }
                System.out.println();
            }
            
            // update state stats
			aggregate(AGGREGATOR_STATE, new DoubleWritable(currentState));
			return newPartition;
		}
		@Override
		public void compute(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				Iterable<PartitionMessage> messages) throws IOException {
			boolean isActive = messages.iterator().hasNext();
			short currentPartition = vertex.getValue().getCurrentPartition();
			//int numberOfEdges = vertex.getNumEdges();
            int numberOfEdges = (int) vertex.getValue().getNumDirectedEdges();

			// update neighbors partitions
			updateNeighborsPartitions(vertex, messages);
			// count labels occurrences in the neighborhood
			int totalLabels = computeNeighborsLabels(vertex);
			// compute the most attractive partition
			short newPartition = computeNewPartition(vertex, totalLabels);
			// request migration to the new destination
            //if (newPartition != currentPartition && isActive) {
			if (newPartition != currentPartition && isActive) {
				requestMigration(vertex, numberOfEdges, currentPartition,
						newPartition);
			}
		}
		@Override
		public void preSuperstep() {
			additionalCapacity = getContext().getConfiguration().getFloat(
					ADDITIONAL_CAPACITY, DEFAULT_ADDITIONAL_CAPACITY);
			numberOfPartitions = (short) getContext().getConfiguration()
					.getInt(NUM_PARTITIONS, DEFAULT_NUM_PARTITIONS);
			repartition = (short) getContext().getConfiguration().getInt(
					REPARTITION, DEFAULT_REPARTITION);
			lambda = getContext().getConfiguration().getFloat(LAMBDA,
					DEFAULT_LAMBDA);
			partitionFrequency = new int[numberOfPartitions + repartition];
			loads = new long[numberOfPartitions + repartition];
			demandAggregatorNames = new String[numberOfPartitions + repartition];
            
            numDirectedEdges = (long) ((LongWritable) getAggregatedValue(DIRECTED_EDGES)).get();
            totalCapacity = (long) Math.round(((double) numDirectedEdges * (1 + additionalCapacity) / (numberOfPartitions + repartition)));
            defaultCapacity = (long) Math.round(((double) numDirectedEdges / (numberOfPartitions + repartition)));
			//totalCapacity = (long) Math.round(((double) getTotalNumEdges() * (1 + additionalCapacity) / (numberOfPartitions + repartition)));
			// cache loads for the penalty function
			for (int i = 0; i < numberOfPartitions + repartition; i++) {
				demandAggregatorNames[i] = AGGREGATOR_DEMAND_PREFIX + i;
				loads[i] = ((LongWritable) getAggregatedValue(AGGREGATOR_LOAD_PREFIX
						+ i)).get();
			}
            
            maxIterations = getContext().getConfiguration().getInt(MAX_ITERATIONS, DEFAULT_MAX_ITERATIONS);
            
		}
        
        
        
        private int maxIterations;        
        private double alpha;
        private double beta;
        private DoubleArrayList LAProbability;
        private DoubleArrayList LASignal;
           
           
        private short maxIndex(DoubleArrayList arrayList) {
            short index = 0;
            for(short i = 1; i < arrayList.size(); i++) {
                if(arrayList.get(i) > arrayList.get(index)) {
                    index = i;
                }
            }
            return index;
        }   
           
        private double maxValue(DoubleArrayList arrayList) {
            return arrayList.get(maxIndex(arrayList));
        }
        
        private double sum(DoubleArrayList arrayList) {
            double sum = 0;
            for(short i = 0; i < arrayList.size(); i++) {
                sum += arrayList.get(i);
            }
            return sum;
        }
        
        
        private short rouletteWheel(DoubleArrayList probability) {
        short returnedIndex = -1;
        short numEvents = (short) probability.size();
        double maxProbability = maxValue(probability);
        double epsilon = 1e-6;
        short scale = 4;
        short numberOfRandomNumbers = (short) (scale * numEvents);
        
        if((1 - maxProbability) < epsilon) {
            returnedIndex = maxIndex(probability);
        } else {
            ShortArrayList sequence = new ShortArrayList();
            for(short i = 0; i < numEvents; i++) {
                if (probability.get(i) > epsilon) {
                    short times = (short) (numberOfRandomNumbers * probability.get(i));
                    for(short j = 0; j < times; j++) {
                        sequence.add(i);
                    }
                }
            }
            Collections.shuffle(sequence);
            short randomNumber = (short) rnd.nextInt(sequence.size()); ///-----
            returnedIndex = sequence.get(randomNumber);
        }
        return(returnedIndex);
    }
        
        
        /*
		 * LA action selection for choosing a new partition
		 */
        private short actionSelection(DoubleArrayList probability) {
            short returnedIndex = -1;
            short numEvents = (short) probability.size();
            double maxProbability = maxValue(probability);
            double epsilon = 1e-6;
            
            if((1 - maxProbability) < epsilon) {
                returnedIndex = maxIndex(probability);
            } else {
                DoubleArrayList tempProbabilities = new DoubleArrayList(probability);
                ShortArrayList tempIndices = new ShortArrayList();
                
                for(short i = 0; i < numEvents; i++) {
                    tempIndices.add(i);
                }
                
                short factor = 2;
                double seprator = (double) 1/factor;
                double randomNumber;
                
                while(numEvents > factor) {                    
                    randomNumber = rnd.nextDouble();
                    
                    short leftMax = 0;
                    short leftNum = 0;
                    short rightMin = 0;
                    short rightNum = 0;
                    short minIndex = tempIndices.get(0);
                    double sumProbabilities = 0;
                    short k = 0;
                    short j = 0;
                    
                    do 
                    {
                        sumProbabilities += tempProbabilities.get(k);
                        k++;
                    } while((Math.abs(sumProbabilities - seprator) > epsilon) && (sumProbabilities < seprator));
                    
                    rightMin = k;
                    leftMax = (short) (rightMin - 1);
                    leftNum = (short) (leftMax + 1);
                    rightNum = (short) (numEvents - leftNum);
                    if(Math.abs(sumProbabilities - seprator) > epsilon) {
                        rightMin--;
                        rightNum++;
                    }	

                    if(randomNumber < seprator) {                        
                        DoubleArrayList leftProbabilities = new DoubleArrayList();
                        ShortArrayList leftIndices = new ShortArrayList();
                        
                        for(k = leftMax; k >= leftNum - leftMax - 1; k--) {
                            j = (short) (leftNum - k - 1);
                            leftProbabilities.add(tempProbabilities.get(j));
                            leftIndices.add((short) (j + minIndex));
                        }

                        if(Math.abs(sumProbabilities - seprator) > epsilon) {
                            leftProbabilities.set(leftNum - 1, leftProbabilities.get(leftNum - 1) - (sum(leftProbabilities) - seprator));
                        }

                        for(k = 0; k < leftNum; k++) {   
                            leftProbabilities.set(k, leftProbabilities.get(k) * factor);
                        }
                        
                        tempProbabilities = new DoubleArrayList(leftProbabilities);
                        tempIndices = new ShortArrayList(leftIndices);
                        numEvents = leftNum;                    
                    } else {
                        DoubleArrayList rightProbabilities = new DoubleArrayList();
                        ShortArrayList rightIndices = new ShortArrayList();
                        
                        for(k = rightMin; k < rightNum + rightMin; k++) {
                            j = (short) (k - rightMin);
                            rightProbabilities.add(tempProbabilities.get(k));
                            rightIndices.add((short) (k + minIndex));
                        }
                        
                        if(Math.abs(sumProbabilities - seprator) > epsilon) {
                            rightProbabilities.set(0, rightProbabilities.get(0) - (sum(rightProbabilities) - seprator));
                        }
                        
                        for(k = 0; k < rightNum; k++) {   
                            rightProbabilities.set(k, rightProbabilities.get(k) * factor);                    
                        }
                        tempProbabilities = new DoubleArrayList(rightProbabilities);
                        tempIndices = new ShortArrayList(rightIndices);
                        numEvents = rightNum;
                    }
                }
                
                if(numEvents == 1) {
                    returnedIndex = tempIndices.get(0);
                } else if(numEvents == factor) {
                    randomNumber = rnd.nextDouble();
                    if(randomNumber < tempProbabilities.get(0)) {
                        returnedIndex = tempIndices.get(0);
                    } else {
                        returnedIndex = tempIndices.get(1);
                    }
                }
            }
            return returnedIndex;
        }
        
        
        
	}
	public static class ComputeMigration
			extends
			AbstractComputation<LongWritable, VertexValue, EdgeValue, PartitionMessage, PartitionMessage> {
		private Random rnd = new Random();
		private String[] loadAggregatorNames;
		private double[] migrationProbabilities;
		private short numberOfPartitions;
		private short repartition;
		private double additionalCapacity;
        private long numDirectedEdges;
        long[] load_print;
        long[] demand_print;
        long[] remain_print;
        long totalCapacity;
        
		private void migrate(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				short currentPartition, short newPartition) {
			vertex.getValue().setCurrentPartition(newPartition);
			// update partitions loads
			//int numberOfEdges = vertex.getNumEdges();
            int numberOfEdges = (int) vertex.getValue().getNumDirectedEdges();
			aggregate(loadAggregatorNames[currentPartition], new LongWritable(
					-numberOfEdges));
			aggregate(loadAggregatorNames[newPartition], new LongWritable(
					numberOfEdges));
			aggregate(AGGREGATOR_MIGRATIONS, new LongWritable(1));
			// inform the neighbors
			PartitionMessage message = new PartitionMessage(vertex.getId()
					.get(), newPartition);
			sendMessageToAllEdges(vertex, message);
		}
        
		@Override
		public void compute(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				Iterable<PartitionMessage> messages) throws IOException {
            /*
			if (messages.iterator().hasNext()) {
				throw new RuntimeException("messages in the migration step!");
			}
            */
            
            updateNeighborsSignals(vertex, messages);
			LAProbability = vertex.getValue().getLAProbability();
            LASignal = vertex.getValue().getLASignal();
            
            if(vertex.getId().get()  == 0) {
                System.out.println("Superstep(" + getSuperstep() + ")-Vertex(" + vertex.getId().get() + ")");
                System.out.println("    LAProbability " + LAProbability);
                System.out.println("    LASignal " + LASignal);
            }
            weightedProbabilityUpdate(vertex, LAProbability, LASignal);
            //if(vertex.getId().get()  == 0) {
            //    System.out.println("    LAProbability " + LAProbability);
            //    System.out.println("    LASignal " + LASignal);
            //}
            if(vertex.getId().get()  == 0) {
                System.out.print("    Capacity ");
                for(int i = 0; i < numberOfPartitions + repartition; i++) {
                    System.out.print(totalCapacity + " ");
                }
                System.out.println();
                System.out.print("    loads ");
                for(int i = 0; i < numberOfPartitions + repartition; i++) {
                    System.out.print(load_print[i] + " ");
                }
                System.out.println();
                System.out.print("    demands ");
                for(int i = 0; i < numberOfPartitions + repartition; i++) {
                    System.out.print(demand_print[i] + " ");
                }
                System.out.println();
                System.out.print("    Remaining ");
                for(int i = 0; i < numberOfPartitions + repartition; i++) {
                    System.out.print(remain_print[i] + " ");
                }
                System.out.println();
                System.out.print("    migrationProbabilities ");
                for(int i = 0; i < numberOfPartitions + repartition; i++) {
                    System.out.print(migrationProbabilities[i] + " ");
                }
                System.out.println();
            }
            
			short currentPartition = vertex.getValue().getCurrentPartition();
			short newPartition = vertex.getValue().getNewPartition();
			if (currentPartition == newPartition) {
				return;
			}
			double migrationProbability = migrationProbabilities[newPartition];
			if (rnd.nextDouble() < migrationProbability) {
				migrate(vertex, currentPartition, newPartition);
			} else {
				vertex.getValue().setNewPartition(currentPartition);
			}
            if(vertex.getId().get()  == 0) {
                System.out.println("current: " + vertex.getValue().getCurrentPartition() + " new: " + vertex.getValue().getNewPartition());
            }
            
		}
		@Override
		public void preSuperstep() {
			additionalCapacity = getContext().getConfiguration().getFloat(
					ADDITIONAL_CAPACITY, DEFAULT_ADDITIONAL_CAPACITY);
			numberOfPartitions = (short) getContext().getConfiguration()
					.getInt(NUM_PARTITIONS, DEFAULT_NUM_PARTITIONS);
			repartition = (short) getContext().getConfiguration().getInt(
					REPARTITION, DEFAULT_REPARTITION);
                    
            numDirectedEdges = (long) ((LongWritable) getAggregatedValue(DIRECTED_EDGES)).get();        
			totalCapacity = (long) Math.round(((double) numDirectedEdges * (1 + additionalCapacity) / (numberOfPartitions + repartition)));
            
            //long totalCapacity = (long) Math.round(((double) getTotalNumEdges()* (1 + additionalCapacity) / (numberOfPartitions + repartition)));
            
			migrationProbabilities = new double[numberOfPartitions
					+ repartition];
			loadAggregatorNames = new String[numberOfPartitions + repartition];
            load_print = new long[numberOfPartitions + repartition];
            demand_print = new long[numberOfPartitions + repartition];
            remain_print = new long[numberOfPartitions + repartition];
			// cache migration probabilities per destination partition
			for (int i = 0; i < numberOfPartitions + repartition; i++) {
				loadAggregatorNames[i] = AGGREGATOR_LOAD_PREFIX + i;
				long load = ((LongWritable) getAggregatedValue(loadAggregatorNames[i]))
						.get();
				long demand = ((LongWritable) getAggregatedValue(AGGREGATOR_DEMAND_PREFIX
						+ i)).get();
				long remainingCapacity = totalCapacity - load;
				if (demand == 0 || remainingCapacity <= 0) {
					migrationProbabilities[i] = 0;
				} else {
					migrationProbabilities[i] = ((double) (remainingCapacity)) / demand;
                    if(migrationProbabilities[i] > 1) {
                        migrationProbabilities[i] = 1;
                    }
				}
                load_print[i] = load;
                demand_print[i] = demand;
                remain_print[i] = remainingCapacity;
			}
            
            alpha = getContext().getConfiguration().getFloat(ALPHA, DEFAULT_ALPHA);
            beta = getContext().getConfiguration().getFloat(BETA, DEFAULT_BETA);
            maxIterations = getContext().getConfiguration().getInt(MAX_ITERATIONS, DEFAULT_MAX_ITERATIONS);
            
		}
        
        
        private double alpha;
        private double beta;
        private DoubleArrayList LAProbability;
        private DoubleArrayList LASignal;
        private int maxIterations;
        
        private short maxIndex(DoubleArrayList arrayList) {
            short index = 0;
            for(short i = 1; i < arrayList.size(); i++) {
                if(arrayList.get(i) > arrayList.get(index)) {
                    index = i;
                }
            }
            return index;
        }
        
        private double sum(long[] array) {
            long sum = 0;
            for(short i = 0; i < array.length; i++) {
                sum += array[i];
            }
            return sum;
        }        
        
        private double sum(DoubleArrayList arrayList) {
            double sum = 0;
            for(short i = 0; i < arrayList.size(); i++) {
                sum += arrayList.get(i);
            }
            return sum;
        }        


        private void updateNeighborsSignals(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				Iterable<PartitionMessage> messages) {
            short i = 0; 
            short maxPartition = 0;
            double newSignal = 0;
            LASignal = vertex.getValue().getLASignal();      
            short newPartition = vertex.getValue().getNewPartition();
            short currentPartition = vertex.getValue().getCurrentPartition();
            HashMap<Long, Boolean> map = vertex.getValue().getDirectedEdges();
			for (PartitionMessage message : messages) {
                maxPartition = message.getPartition();
                newSignal = message.getSignal();
                double migrationProbability = migrationProbabilities[maxPartition];
                if((maxPartition == newPartition) || (migrationProbability > 0)) {
                //if(map.get(e.getTargetVertexId().get())) {    
                    LASignal.set(maxPartition, LASignal.get(maxPartition) + newSignal);
                }
                //}
                //if(vertex.getId().get()  == 0) {
                //    System.out.print(message.getSourceId() + " " + message.getPartition() + " " + message.getSignal() + ", ");
                //}
			}
            //if(vertex.getId().get() == 0) {
            //    System.out.println();
            //}
		}    

        
        private void weightedProbabilityUpdate(Vertex<LongWritable, VertexValue, EdgeValue> vertex,
                     DoubleArrayList probability, DoubleArrayList signal) {
            short numEvents = (short) probability.size();
            //double seprator = signal.get(maxIndex(signal)) - 1;
            double seprator = (double) sum(signal)/numEvents;
            DoubleArrayList positiveSignals = new DoubleArrayList();
            ShortArrayList positiveIndices = new ShortArrayList();
            DoubleArrayList negativeSignals = new DoubleArrayList();
            ShortArrayList negativeIndices = new ShortArrayList();

            
            short maxSignalIndex = maxIndex(signal); 
            //short maxSignalIndex = vertex.getValue().getNewPartition();
            double w0 = 0.9;
            double w1 = 0.4;
            double w = (double) ((w0 - w1) * getSuperstep() * Math.sqrt(numEvents)) / maxIterations;
            signal.set(maxSignalIndex, signal.get(maxSignalIndex)+ (signal.get(maxSignalIndex) * w));
            
            
            for(short i = 0; i < numEvents; i++){
                if(signal.get(i) >= seprator) {
                    positiveSignals.add(signal.get(i));
                    positiveIndices.add(i);
                } else {
                    negativeSignals.add(signal.get(i));
                    negativeIndices.add(i);
                }
            }
            
            short positiveNum = (short) positiveSignals.size();
            double sumPositiveSignals = sum(positiveSignals);
            if(sumPositiveSignals > 0) {
                for(short i = 0; i < positiveNum; i++) {
                    positiveSignals.set(i, (double) positiveSignals.get(i) / sumPositiveSignals);
                }
                bubbleSort(positiveSignals, positiveIndices);
            }
            
            
            short negativeNum = (short) negativeSignals.size();
            double sumNegativeSignals = sum(negativeSignals);
            if(sumNegativeSignals > 0) {
                for(short i = 0; i < negativeNum; i++) {
                    negativeSignals.set(i, (double) negativeSignals.get(i) / sumNegativeSignals);
                }
                bubbleSort(negativeSignals, negativeIndices);
            } else { // In case we have negative numbers
                for(short i = 0; i < negativeNum; i++) {
                    negativeSignals.set(i, (double) 1 / negativeNum);
                }
            }
            
            
            if(vertex.getId().get()  == 0) {
                System.out.println("    Seprator " + seprator);
                System.out.println("    Original signal" + signal);
                System.out.println("    Negative signal" + negativeSignals);
                System.out.println("    Negative indices" + negativeIndices);
                System.out.println("    Positive signal" + positiveSignals);
                System.out.println("    Positive indices" + positiveIndices);
            }
            
            short index;
            
            for(short i = 0; i < negativeNum; i++) {
                index = negativeIndices.get(i);
                probability.set(index, (double) probability.get(index) * (1 - (negativeSignals.get(i) * beta)));
                for(short j = 0; j < numEvents; j++) {
                    if(j != index) {
                        probability.set(j, (double) (((double) (negativeSignals.get(i) * beta)/(numEvents - 1)) + ((1 - (negativeSignals.get(i) * beta)) * probability.get(j))));
                    }
                }                
            }
            
            for(short i = 0; i < positiveNum; i++) {
                index = positiveIndices.get(i);
                probability.set(index,(double) (probability.get(index) + ((positiveSignals.get(i) * alpha) * ( 1 - probability.get(index)))));
                for(short j = 0; j < numEvents; j++) {
                    if(j != index) {
                        probability.set(j, probability.get(j) * (1 - (positiveSignals.get(i) * alpha)));
                    }
                }         
            }
            
            
            /*
            double sumSignal = sum(signal);
            for(short i = 0; i < numEvents; i++) {
                signal.set(i, (double) signal.get(i) / sumSignal);
            }
            
            short maxPartition = maxIndex(signal);
			short newPartition = vertex.getValue().getNewPartition();
            
            for(short j = 0; j < numEvents; j++) {
                if((newPartition == j) || (migrationProbabilities[j] > 0)) {
                    for(short i = 0; i < numEvents; i++) {
                        if(i == newPartition) {
                            probability.set(i, (double) probability.get(i) * (1 - (signal.get(j) * beta)));
                        } else {
                            probability.set(i, (double) (((double) (signal.get(j) * beta)/(numEvents - 1)) + ((1 - (signal.get(j) * beta)) * probability.get(i))));
                        }
                    }
                } else {
                    for(short i = 0; i < numEvents; i++) {
                        if(i == newPartition) {
                            probability.set(i, (double) (probability.get(i) + ((signal.get(j) * alpha) * (1 - probability.get(i)))));
                        } else {
                            probability.set(i, probability.get(i) * (1 - (signal.get(j) * alpha)));
                        }
                    }
                }
            }
            */
            
            //if(vertex.getId().get() == 0) {
            //    System.out.println("   Sum: " + sum(probability));
                
            //}
            
            for(short i = 0; i < numEvents; i++) {
                signal.set(i,0);
            }
        }



        private static void bubbleSort(DoubleArrayList values, ShortArrayList indices) {
            short length = (short) values.size();
            for(short k = 0; k < length; k++) {
                for(short l = (short) (k + 1); l < length; l++) {
                    if(values.get(k) > values.get(l)) {
                        double temp_value = values.get(k);
                        values.set(k, values.get(l));
                        values.set(l, temp_value);
                        short temp_index = indices.get(k);
                        indices.set(k, indices.get(l));
                        indices.set(l, temp_index);
                    }
                }
            }
        }    
        
        
	}
	public static class Initializer
			extends
			AbstractComputation<LongWritable, VertexValue, EdgeValue, PartitionMessage, PartitionMessage> {
		private Random rnd = new Random();
		private String[] loadAggregatorNames;
		private int numberOfPartitions;
		@Override
		public void compute(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				Iterable<PartitionMessage> messages) throws IOException {
			short partition = vertex.getValue().getCurrentPartition();
			if (partition == -1) {
				partition = (short) rnd.nextInt(numberOfPartitions);
			}
            
            for (int i = 0; i < numberOfPartitions; i++) {
                vertex.getValue().addLAProbability((double) 1 / numberOfPartitions);
                vertex.getValue().addLASignal(0);
            }
            
			aggregate(loadAggregatorNames[partition], new LongWritable(vertex.getValue().getNumDirectedEdges()));
            //aggregate(loadAggregatorNames[partition], new LongWritable(vertex.getNumEdges()));
			vertex.getValue().setCurrentPartition(partition);
			vertex.getValue().setNewPartition(partition);
			PartitionMessage message = new PartitionMessage(vertex.getId()
					.get(), partition);
			sendMessageToAllEdges(vertex, message);
		}
		@Override
		public void preSuperstep() {
			numberOfPartitions = getContext().getConfiguration().getInt(
					NUM_PARTITIONS, DEFAULT_NUM_PARTITIONS);
			loadAggregatorNames = new String[numberOfPartitions];
			for (int i = 0; i < numberOfPartitions; i++) {
				loadAggregatorNames[i] = AGGREGATOR_LOAD_PREFIX + i;
			}
		}
	}
	public static class ConverterPropagate
			extends
			AbstractComputation<LongWritable, VertexValue, EdgeValue, LongWritable, LongWritable> {
		@Override
		public void compute(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				Iterable<LongWritable> messages) throws IOException {
			sendMessageToAllEdges(vertex, vertex.getId());
		}
	}
    
	public static class Repartitioner
			extends
			AbstractComputation<LongWritable, VertexValue, EdgeValue, PartitionMessage, PartitionMessage> {
		private Random rnd = new Random();
		private String[] loadAggregatorNames;
		private int numberOfPartitions;
		private short repartition;
		private double migrationProbability;
		@Override
		public void compute(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				Iterable<PartitionMessage> messages) throws IOException {
			short partition;
			short currentPartition = vertex.getValue().getCurrentPartition();
			// down-scale
			if (repartition < 0) {
				if (currentPartition >= numberOfPartitions + repartition) {
					partition = (short) rnd.nextInt(numberOfPartitions
							+ repartition);
				} else {
					partition = currentPartition;
				}
				// up-scale
			} else if (repartition > 0) {
				if (rnd.nextDouble() < migrationProbability) {
					partition = (short) (numberOfPartitions + rnd
							.nextInt(repartition));
				} else {
					partition = currentPartition;
				}
			} else {
				throw new RuntimeException("Repartitioner called with "
						+ REPARTITION + " set to 0");
			}
			//aggregate(loadAggregatorNames[partition], new LongWritable(vertex.getNumEdges()));
            aggregate(loadAggregatorNames[partition], new LongWritable(vertex.getValue().getNumDirectedEdges()));
			vertex.getValue().setCurrentPartition(partition);
			vertex.getValue().setNewPartition(partition);
			PartitionMessage message = new PartitionMessage(vertex.getId()
					.get(), partition);
			sendMessageToAllEdges(vertex, message);
		}
		@Override
		public void preSuperstep() {
			numberOfPartitions = getContext().getConfiguration().getInt(
					NUM_PARTITIONS, DEFAULT_NUM_PARTITIONS);
			repartition = (short) getContext().getConfiguration().getInt(
					REPARTITION, DEFAULT_REPARTITION);
			migrationProbability = ((double) repartition)
					/ (repartition + numberOfPartitions);
			loadAggregatorNames = new String[numberOfPartitions + repartition];
			for (int i = 0; i < numberOfPartitions + repartition; i++) {
				loadAggregatorNames[i] = AGGREGATOR_LOAD_PREFIX + i;
			}
		}
	}
    
    	public static class ConverterUpdateEdges
			extends
			AbstractComputation<LongWritable, VertexValue, EdgeValue, LongWritable, PartitionMessage> {
		private byte edgeWeight;
        private long numDirectedEdges;

		@Override
		public void compute(
				Vertex<LongWritable, VertexValue, EdgeValue> vertex,
				Iterable<LongWritable> messages) throws IOException {
            
            HashMap<Long, Boolean> map = vertex.getValue().getDirectedEdges();      
            for (Edge<LongWritable, EdgeValue> e : vertex.getEdges()) {
                    map.put((long) e.getTargetVertexId().get(), (boolean) true);
                    vertex.getValue().numDirectedEdges++;
            } 
            
            aggregate(DIRECTED_EDGES, new LongWritable(vertex.getValue().numDirectedEdges));
            
            /*
            if(vertex.getId().get() == 0) {
                for (Edge<LongWritable, EdgeValue> e : vertex.getEdges()) {
                    System.out.print("[" + e.getTargetVertexId() + ", " + e.getValue().getPartition() + ", " + e.getValue().getWeight() + ", " + map.get(e.getTargetVertexId().get()) + "] ");
                } 
                System.out.println(" " + vertex.getValue().numDirectedEdges);                
            }
            */
                    
             
			for (LongWritable other : messages) {
				EdgeValue edgeValue = vertex.getEdgeValue(other);
                
                //if(vertex.getId().get() == 0) {
                //    System.out.println(other + " " + vertex.getEdgeValue(other) + " " + vertex.getEdgeValue(other).getPartition());                
                //}
                
				//if (edgeValue == null) {
                if (edgeValue.getPartition() == 0) {
					edgeValue = new EdgeValue();
					edgeValue.setWeight((byte) edgeWeight);
					Edge<LongWritable, EdgeValue> edge = EdgeFactory.create(new LongWritable(other.get()), edgeValue);
					vertex.addEdge(edge);
                    
                    map.put((long) other.get(), (boolean) false);
				} else {
					edgeValue = new EdgeValue();
					edgeValue.setWeight((byte) edgeWeight);
					vertex.setEdgeValue(other, edgeValue);
                    
                    map.put((long) other.get(), (boolean) true);
				}
			}
            
            /*
            if(vertex.getId().get() == 0) {
                for (Edge<LongWritable, EdgeValue> e : vertex.getEdges()) {
                    System.out.print("[" + e.getTargetVertexId() + ", " + e.getValue().getPartition() + ", " + e.getValue().getWeight() + ", " + map.get(e.getTargetVertexId().get()) + "] ");
                } 
                System.out.println(" " + vertex.getValue().numDirectedEdges);                
            }
            */
            
		}

		@Override
		public void preSuperstep() {
			edgeWeight = (byte) getContext().getConfiguration().getInt(
					EDGE_WEIGHT, DEFAULT_EDGE_WEIGHT);
		}       
	}

	public static class PartitionerMasterCompute extends DefaultMasterCompute {
		private LinkedList<Double> states;
		private String[] loadAggregatorNames;
		private int maxIterations;
		private int numberOfPartitions;
		private double convergenceThreshold;
		private short repartition;
		private int windowSize;
		private long totalMigrations;
		private double maxMinLoad;
		private double maxNormLoad;
		private double score;
		@Override
		public void initialize() throws InstantiationException,
				IllegalAccessException {
			maxIterations = getContext().getConfiguration().getInt(
					MAX_ITERATIONS, DEFAULT_MAX_ITERATIONS);
			numberOfPartitions = getContext().getConfiguration().getInt(
					NUM_PARTITIONS, DEFAULT_NUM_PARTITIONS);
			convergenceThreshold = getContext().getConfiguration().getFloat(
					CONVERGENCE_THRESHOLD, DEFAULT_CONVERGENCE_THRESHOLD);
			repartition = (short) getContext().getConfiguration().getInt(
					REPARTITION, DEFAULT_REPARTITION);
			windowSize = (int) getContext().getConfiguration().getInt(
					WINDOW_SIZE, DEFAULT_WINDOW_SIZE);
			states = Lists.newLinkedList();
			// Create aggregators for each partition
			loadAggregatorNames = new String[numberOfPartitions + repartition];
			for (int i = 0; i < numberOfPartitions + repartition; i++) {
				loadAggregatorNames[i] = AGGREGATOR_LOAD_PREFIX + i;
				registerPersistentAggregator(loadAggregatorNames[i],
						LongSumAggregator.class);
				registerAggregator(AGGREGATOR_DEMAND_PREFIX + i,
						LongSumAggregator.class);
			}
			registerAggregator(AGGREGATOR_STATE, DoubleSumAggregator.class);
			registerAggregator(AGGREGATOR_LOCALS, LongSumAggregator.class);
			registerAggregator(AGGREGATOR_MIGRATIONS, LongSumAggregator.class);
            
            registerAggregator(AGGREGATOR_CUT, LongSumAggregator.class);
            registerPersistentAggregator(DIRECTED_EDGES, LongSumAggregator.class);
		}
		private void printStats(int superstep) {
			System.out.println("superstep " + superstep);
			long migrations = ((LongWritable) getAggregatedValue(AGGREGATOR_MIGRATIONS))
					.get();
			long localEdges = ((LongWritable) getAggregatedValue(AGGREGATOR_LOCALS))
					.get();
            long localCut = ((LongWritable) getAggregatedValue(AGGREGATOR_CUT))
                    .get();
            
            long directedEdges = ((LongWritable) getAggregatedValue(DIRECTED_EDGES))
                    .get();
                    
			if (superstep > 2) {
				switch (superstep % 2) {
				case 0:
					//System.out.println(((double) localEdges) / getTotalNumEdges() + " local edges");
                    System.out.println(((double) localEdges) / directedEdges + " local edges");
                    System.out.println(localCut + " cut edges");
                    //System.out.println(directedEdges + " directed edges");
					long minLoad = Long.MAX_VALUE;
					long maxLoad = -Long.MAX_VALUE;
					for (int i = 0; i < numberOfPartitions + repartition; i++) {
						long load = ((LongWritable) getAggregatedValue(loadAggregatorNames[i]))
								.get();
						if (load < minLoad) {
							minLoad = load;
						}
						if (load > maxLoad) {
							maxLoad = load;
						}
					}
					//double expectedLoad = ((double) getTotalNumEdges()) / (numberOfPartitions + repartition);
                    double expectedLoad = ((double) directedEdges) / (numberOfPartitions + repartition);
                                    
					System.out.println((((double) maxLoad) / minLoad)
							+ " max-min unbalance");
					System.out.println((((double) maxLoad) / expectedLoad)
							+ " maximum normalized load");
					break;
				case 1:
					System.out.println(migrations + " migrations");
					break;
				}
			}
		}
		private boolean algorithmConverged(int superstep) {
            
            double newState = ((DoubleWritable) getAggregatedValue(AGGREGATOR_STATE)).get();
            states.addLast(newState);
            return false;
            
            /*
            double newState = ((DoubleWritable) getAggregatedValue(AGGREGATOR_STATE)).get();
                double oldState;
                boolean converged = false;
            if(states.size() == 0) {
                states.addFirst(newState);
            } else {
                    oldState = states.getLast();
                    if(newState - oldState < convergenceThreshold) {
                        states.addLast(newState);
                            if(states.size() > windowSize) {
                                    converged = true;
                            }
                    } else {
                            states.clear();
                            states.addFirst(newState);
                    }
                }
                return converged;
            */    
                 
			/*
			double newState = ((DoubleWritable) getAggregatedValue(AGGREGATOR_STATE))
					.get();
			boolean converged = false;
			if (superstep > 3 + windowSize) {
				double best = Collections.max(states);
				double step = Math.abs(1 - newState / best);
				converged = step < convergenceThreshold;
				System.out.println("BestState=" + best + " NewState="
						+ newState + " " + step);
				states.removeFirst();
			} else {
				System.out.println("BestState=" + newState + " NewState="
						+ newState + " " + 1.0);
			}
			states.addLast(newState);
			return converged;
			*/
		}
		private void setCounters() {
			long localEdges = ((LongWritable) getAggregatedValue(AGGREGATOR_LOCALS))
					.get();
            long directedEdges = ((LongWritable) getAggregatedValue(DIRECTED_EDGES))
                    .get();                    
			//long localEdgesPct = (long) (100 * ((double) localEdges) / getTotalNumEdges());
            long localEdgesPct = (long) (100 * ((double) localEdges) / directedEdges);
            long localCut = ((LongWritable) getAggregatedValue(AGGREGATOR_CUT))
                    .get();              
              
                    
            getContext().getCounter(COUNTER_GROUP, AGGREGATOR_CUT)
					.increment(localCut);
            getContext().getCounter(COUNTER_GROUP, DIRECTED_EDGES)
					.increment(directedEdges);        
			getContext().getCounter(COUNTER_GROUP, MIGRATIONS_COUNTER)
					.increment(totalMigrations);
			getContext().getCounter(COUNTER_GROUP, ITERATIONS_COUNTER)
					.increment(getSuperstep());
			getContext().getCounter(COUNTER_GROUP, PCT_LOCAL_EDGES_COUNTER)
					.increment(localEdgesPct);
			getContext().getCounter(COUNTER_GROUP, MAXMIN_UNBALANCE_COUNTER)
					.increment((long) (1000 * maxMinLoad));
			getContext().getCounter(COUNTER_GROUP,
					MAX_NORMALIZED_UNBALANCE_COUNTER).increment(
					(long) (1000 * maxNormLoad));
			getContext().getCounter(COUNTER_GROUP, SCORE_COUNTER).increment(
					(long) (1000 * score));
		}
		private void updateStats() {
			totalMigrations += ((LongWritable) getAggregatedValue(AGGREGATOR_MIGRATIONS))
					.get();
            long directedEdges = ((LongWritable) getAggregatedValue(DIRECTED_EDGES))
                    .get();     
			long minLoad = Long.MAX_VALUE;
			long maxLoad = -Long.MAX_VALUE;
			for (int i = 0; i < numberOfPartitions + repartition; i++) {
				long load = ((LongWritable) getAggregatedValue(loadAggregatorNames[i]))
						.get();
				if (load < minLoad) {
					minLoad = load;
				}
				if (load > maxLoad) {
					maxLoad = load;
				}
			}
			maxMinLoad = ((double) maxLoad) / minLoad;
			//double expectedLoad = ((double) getTotalNumEdges()) / (numberOfPartitions + repartition);
            double expectedLoad = ((double) directedEdges) / (numberOfPartitions + repartition);
			maxNormLoad = ((double) maxLoad) / expectedLoad;
			score = ((DoubleWritable) getAggregatedValue(AGGREGATOR_STATE))
					.get();
		}
		@Override
		public void compute() {
			int superstep = (int) getSuperstep();
			if (superstep == 0) {
				setComputation(ConverterPropagate.class);
			} else if (superstep == 1) {
				setComputation(ConverterUpdateEdges.class);
			} else if (superstep == 2) {
				if (repartition != 0) {
					setComputation(Repartitioner.class);
				} else {
					setComputation(Initializer.class);
				}
			} else {
				switch (superstep % 2) {
				case 0:
					setComputation(ComputeMigration.class);
					break;
				case 1:
					setComputation(ComputeNewPartition.class);
					break;
				}
			}
			boolean hasConverged = false;
			if (superstep > 3) {
				if (superstep % 2 == 0) {
					hasConverged = algorithmConverged(superstep);
				}
			}
			printStats(superstep);
			updateStats();
			if (hasConverged || superstep >= maxIterations) {
				System.out.println("Halting computation: " + hasConverged);
				haltComputation();
				setCounters();
			}
		}
	}
    
	public static class EdgeValue implements Writable {
		private short partition = -1;
		private byte weight = 1;

		public EdgeValue() {
		}

		public EdgeValue(EdgeValue o) {
			this(o.getPartition(), o.getWeight());
		}

		public EdgeValue(short partition, byte weight) {
			setPartition(partition);
			setWeight(weight);
		}

		public short getPartition() {
			return partition;
		}

		public void setPartition(short partition) {
			this.partition = partition;
		}

		public byte getWeight() {
			return weight;
		}

		public void setWeight(byte weight) {
			this.weight = weight;
		}

		@Override
		public void readFields(DataInput in) throws IOException {
			partition = in.readShort();
			weight = in.readByte();
		}

		@Override
		public void write(DataOutput out) throws IOException {
			out.writeShort(partition);
			out.writeByte(weight);
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			EdgeValue that = (EdgeValue) o;
			return this.partition == that.partition;
		}

		@Override
		public String toString() {
			return getWeight() + " " + getPartition();
		}
	}
    
    
	public static class VertexValue implements Writable {
		private short currentPartition = -1;
		private short newPartition = -1;
		public VertexValue() {
		}
		public short getCurrentPartition() {
			return currentPartition;
		}
		public void setCurrentPartition(short p) {
			currentPartition = p;
		}
		public short getNewPartition() {
			return newPartition;
		}
		public void setNewPartition(short p) {
			newPartition = p;
		}
		@Override
		public void readFields(DataInput in) throws IOException {
			currentPartition = in.readShort();
			newPartition = in.readShort();
		}
		@Override
		public void write(DataOutput out) throws IOException {
			out.writeShort(currentPartition);
			out.writeShort(newPartition);
		}
		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			VertexValue that = (VertexValue) o;
			if (currentPartition != that.currentPartition
					|| newPartition != that.newPartition) {
				return false;
			}
			return true;
		}
		@Override
		public String toString() {
			return getCurrentPartition() + " " + getNewPartition();
		}
        
        private DoubleArrayList LAProbability = new DoubleArrayList();
        public void addLAProbability(double probability) {
            LAProbability.add(probability);
        }
        public DoubleArrayList getLAProbability() {
            return(LAProbability);
        }
        
        private DoubleArrayList LASignal = new DoubleArrayList();
        public void addLASignal(double signal) {
            LASignal.add(signal);
        }
        public DoubleArrayList getLASignal() {
            return(LASignal);
        }
        HashMap<Long, Boolean> directedEdges = new HashMap<>();
        public HashMap<Long, Boolean> getDirectedEdges() {
            return(directedEdges);
        }
        private long numDirectedEdges = 0;
        public long getNumDirectedEdges() {
            return(numDirectedEdges);
        }
        
	}
	public static class PartitionMessage implements Writable {
		private long sourceId;
		private short partition;
		public PartitionMessage() {
		}
		public PartitionMessage(long sourceId, short partition) {
			this.sourceId = sourceId;
			this.partition = partition;
		}
		public long getSourceId() {
			return sourceId;
		}
		public void setSourceId(long sourceId) {
			this.sourceId = sourceId;
		}
		public short getPartition() {
			return partition;
		}
		public void setPartition(short partition) {
			this.partition = partition;
		}
		@Override
		public void readFields(DataInput input) throws IOException {
			sourceId = input.readLong();
			partition = input.readShort();
            signal = input.readDouble();
		}
		@Override
		public void write(DataOutput output) throws IOException {
			output.writeLong(sourceId);
			output.writeShort(partition);
            output.writeDouble(signal);
		}
		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}
			PartitionMessage that = (PartitionMessage) o;
			if (partition != that.partition || sourceId != that.sourceId) {
				return false;
			}
			return true;
		}
		@Override
		public String toString() {
			return getSourceId() + " " + getPartition();
		}
        
        private double signal;
        public PartitionMessage(long sourceId, short partition, double signal) {
			this.sourceId = sourceId;
            this.partition = partition;
			this.signal = signal;
		}
        
        public double getSignal() {
			return signal;
		}

		public void setSignal(double signal) {
			this.signal = signal;
		}
	}
    
    
	public static class SpinnerVertexValueInputFormat extends
			TextVertexValueInputFormat<LongWritable, VertexValue, EdgeValue> {
		private static final Pattern SEPARATOR = Pattern.compile("[\001\t ]");
		@Override
		public LongShortTextVertexValueReader createVertexValueReader(
				InputSplit split, TaskAttemptContext context)
				throws IOException {
			return new LongShortTextVertexValueReader();
		}
		public class LongShortTextVertexValueReader extends
				TextVertexValueReaderFromEachLineProcessed<String[]> {
			@Override
			protected String[] preprocessLine(Text line) throws IOException {
				return SEPARATOR.split(line.toString());
			}
			@Override
			protected LongWritable getId(String[] data) throws IOException {
				return new LongWritable(Long.parseLong(data[0]));
			}
			@Override
			protected VertexValue getValue(String[] data) throws IOException {
				VertexValue value = new VertexValue();
				if (data.length > 1) {
					short partition = Short.parseShort(data[1]);
					value.setCurrentPartition(partition);
					value.setNewPartition(partition);
				}
				return value;
			}
		}
	}
    
	public static class SpinnerEdgeInputFormat extends
			TextEdgeInputFormat<LongWritable, EdgeValue> {
		/** Splitter for endpoints */
		private static final Pattern SEPARATOR = Pattern.compile("[\001\t ]");

		@Override
		public EdgeReader<LongWritable, EdgeValue> createEdgeReader(
				InputSplit split, TaskAttemptContext context)
				throws IOException {
			return new SpinnerEdgeReader();
		}

		public class SpinnerEdgeReader extends
				TextEdgeReaderFromEachLineProcessed<String[]> {
			@Override
			protected String[] preprocessLine(Text line) throws IOException {
				return SEPARATOR.split(line.toString());
			}

			@Override
			protected LongWritable getSourceVertexId(String[] endpoints)
					throws IOException {
				return new LongWritable(Long.parseLong(endpoints[0]));
			}

			@Override
			protected LongWritable getTargetVertexId(String[] endpoints)
					throws IOException {
				return new LongWritable(Long.parseLong(endpoints[1]));
			}

			@Override
			protected EdgeValue getValue(String[] endpoints) throws IOException {
				EdgeValue value = new EdgeValue();
				if (endpoints.length == 3) {
					value.setWeight((byte) Byte.parseByte(endpoints[2]));
				}
				return value;
			}
		}
	}
    
	public static class SpinnerVertexValueOutputFormat extends
			TextVertexOutputFormat<LongWritable, VertexValue, EdgeValue> {
		/** Specify the output delimiter */
		public static final String LINE_TOKENIZE_VALUE = "output.delimiter";
		/** Default output delimiter */
		public static final String LINE_TOKENIZE_VALUE_DEFAULT = " ";
		public TextVertexWriter createVertexWriter(TaskAttemptContext context) {
			return new SpinnerVertexValueWriter();
		}
		protected class SpinnerVertexValueWriter extends
				TextVertexWriterToEachLine {
			/** Saved delimiter */
			private String delimiter;
			@Override
			public void initialize(TaskAttemptContext context)
					throws IOException, InterruptedException {
				super.initialize(context);
				Configuration conf = context.getConfiguration();
				delimiter = conf.get(LINE_TOKENIZE_VALUE,
						LINE_TOKENIZE_VALUE_DEFAULT);
			}
			@Override
			protected Text convertVertexToLine(
					Vertex<LongWritable, VertexValue, EdgeValue> vertex)
					throws IOException {
				return new Text(vertex.getId().get() + delimiter
						+ vertex.getValue().getCurrentPartition());
			}
		}
	} }
