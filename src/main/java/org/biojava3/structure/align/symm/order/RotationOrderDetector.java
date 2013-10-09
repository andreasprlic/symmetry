package org.biojava3.structure.align.symm.order;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;

import org.biojava.bio.structure.Atom;
import org.biojava.bio.structure.Calc;
import org.biojava.bio.structure.StructureException;
import org.biojava.bio.structure.StructureTools;
import org.biojava.bio.structure.align.model.AFPChain;
import org.biojava.bio.structure.align.util.RotationAxis;
import org.biojava3.structure.align.symm.CeSymm;

/**
 * Detects order by rotating the structure by angles that correspond to orders.
 * This one could be smart.
 * TODO Needs lots of work
 * @author dmyersturnbull
 */
public class RotationOrderDetector implements OrderDetector {

	private int maxOrder = 8;
	private final double minScore;

	public RotationOrderDetector(double minTmScore) {
		super();
		this.minScore = minTmScore;
	}

	public RotationOrderDetector(int maxOrder, double minTmScore) {
		super();
		this.maxOrder = maxOrder;
		this.minScore = minTmScore;
	}

	@Override
	public int calculateOrder(AFPChain afpChain, Atom[] ca) throws OrderDetectionFailedException {

		try {

			RotationAxis axis = new RotationAxis(afpChain);

			//AFPChain clone = (AFPChain) afpChain.clone();
			Atom[] ca2 = null;

			double bestScore = minScore;
			int argmax = 1;
			
			for (int order = 1; order <= maxOrder; order++) {

				ca2 = StructureTools.cloneCAArray(ca); // reset rotation for new order
				double angle = 2*Math.PI / order; // will apply repeatedly

				/*
				 * If C6, we should be able to rotate 6 times and still get a decent superposition
				 */
				double lowestScore = Double.POSITIVE_INFINITY;
				for (int j = 1; j < order; j++) {
					axis.rotate(ca2, angle); // rotate repeatedly
					//double score = AFPChainScorer.getTMScore(clone, ca, ca2);
					double score = superpositionDistance(ca, ca2);

					if (score < lowestScore) {
						lowestScore = score;
					}
				}

				if (lowestScore > bestScore) {
					bestScore = lowestScore;
					argmax = order;
				}

			}

			return argmax;

		} catch (Exception e) {
			throw new OrderDetectionFailedException(e);
		}
	}

	/**
	 * Provide a rough alignment-free metric for the similarity between two
	 * superimposed structures.
	 *
	 * The average distance from each atom to the closest atom in the other
	 * is used.
	 * @param ca1
	 * @param ca2
	 * @return
	 * @throws StructureException
	 */
	public static double superpositionDistance(Atom[] ca1, Atom[] ca2) throws StructureException {

		// Store the closest distance yet found
		double[] bestDist1 = new double[ca1.length];
		double[] bestDist2 = new double[ca2.length];
		Arrays.fill(bestDist1, Double.POSITIVE_INFINITY);
		Arrays.fill(bestDist2, Double.POSITIVE_INFINITY);

		for(int i=0;i<ca1.length;i++) {
			for(int j=0;j<ca2.length;j++) {
				double dist = Calc.getDistanceFast(ca1[i], ca2[j]);
				if( dist < bestDist1[i]) {
					bestDist1[i] = dist;
				}
				if( dist < bestDist2[j]) {
					bestDist2[j] = dist;
				}
			}
		}

		double total = 0;
		for(int i=0;i<ca1.length;i++) {
			total += Math.sqrt(bestDist1[i]);
		}
		for(int j=0;j<ca2.length;j++) {
			total += Math.sqrt(bestDist2[j]);
		}

		double dist = total/(ca1.length+ca2.length);
		return dist;
	}
	
	public static void main(String[] args) {
		String name;
		name = "d1ijqa1";
//		name = "1G6S";
//		name = "1MER.A";
//		name = "1MER";
//		name = "1TIM.A";
//		name = "d1h70a_";
		PrintStream out = System.out;
		try {
			// Output file
			// Use stdout if the directory doesn't exist
			String filename = "/Users/blivens/dev/bourne/symmetry/order/angle_"+name+".tsv";
			File file = new File(filename);
			if(file.getParentFile().exists()) {
				System.out.println("Writing to "+file.getAbsolutePath());
				out = new PrintStream(file);
			}

			
			Atom[] ca1 = StructureTools.getAtomCAArray(StructureTools.getStructure(name));
			Atom[] ca2 = StructureTools.cloneCAArray(ca1);

			CeSymm ce = new CeSymm();
			
			AFPChain alignment = ce.align(ca1, ca2);
			
			RotationAxis axis = new RotationAxis(alignment);
			
			out.println("Angle\tDistance");
			out.format("%f\t%f%n", 0.,0.);
			double angleIncr = 5*Calc.radiansPerDegree;
			for (double angle = angleIncr; angle < 2*Math.PI; angle += angleIncr) {
				axis.rotate(ca2, angleIncr);
				double score = superpositionDistance(ca1, ca2);
					
				out.format("%f\t%f%n", angle,score);
				//new StructureAlignmentJmol(alignment, ca1, ca2);
			}

			out.close();
		} catch (IOException e) {
			e.printStackTrace();
		} catch (StructureException e) {
			e.printStackTrace();
		}
		
		
		
	}
}
