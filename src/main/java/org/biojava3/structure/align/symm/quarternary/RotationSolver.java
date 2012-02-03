
package org.biojava3.structure.align.symm.quarternary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.vecmath.AxisAngle4d;
import javax.vecmath.Matrix4d;
import javax.vecmath.Point3d;
import javax.vecmath.Vector3d;

/**
 *
 * @author Peter
 */
public class RotationSolver implements QuatSymmetrySolver {
    private Subunits subunits = null;
    private double rmsdThreshold = 3.0f;
    private double subunitRmsdThreshold = 6.0f;
    private double gtsThreshold = 50.0f;
    private boolean pseudoSymmetryAllowed = false;
	private int maxOrder = 60;
    private double distanceThreshold = 0.0f;
    private DistanceBox<Integer> box = null;
    private Vector3d centroid = new Vector3d();
    private Matrix4d centroidInverse = new Matrix4d();
    private Point3d[] originalCoords = null;
    private Point3d[] transformedCoords = null;
    private Set<List<Integer>> hashCodes = new HashSet<List<Integer>>();

    private RotationGroup rotations = new RotationGroup();
    private QuatSuperpositionScorer scorer = null;

    public RotationSolver(Subunits subunits) {
    	if (subunits.getSubunitCount()== 2) {
    		throw new IllegalArgumentException("RotationSolver cannot be applied to subunits with 2 centers");
    	}
        this.subunits = subunits;
    }

    public void setRmsdThreshold(double rmsdThreshold) {
        this.rmsdThreshold = rmsdThreshold;
    }
    
    public void setSubunitRmsdThreshold(double subunitRmsdThreshold) {
        this.subunitRmsdThreshold = subunitRmsdThreshold;
    }

    public void setGtsThreshold(double gtsThreshold) {
        this.gtsThreshold = gtsThreshold;
    }

    public void setPseudoSymmetryAllowed(boolean pseudoSymmetryAllowed) {
		this.pseudoSymmetryAllowed = pseudoSymmetryAllowed;
	}

	public RotationGroup getSymmetryOperations() {
        if (rotations.getOrder() == 0) {
            solve();
            completeRotationGroup();
        }
        return rotations;
    }

    private void solve() {
        initialize();
        
        int maxSymOps = subunits.getSubunitCount();
        // for cases with icosahedral symmetry n cannot be higher than 60, should check for spherical symmetry here
        // isSpherical check added 08-04-11
        if (maxSymOps % 60 == 0 && isSpherical()) {
            maxSymOps = Math.min(maxSymOps, maxOrder);
         }

        AxisAngle4d sphereAngle = new AxisAngle4d();
        Matrix4d transformation = new Matrix4d();

        int n = subunits.getSubunitCount();
        List<Double> angles = getAngles();

       for (int i = 0; i < SphereSampler.getSphereCount(); i++) {
            SphereSampler.getAxisAngle(i, sphereAngle);
            for (double angle : angles) {
                // apply rotation
                sphereAngle.setAngle(angle);
                transformation.set(sphereAngle);
                for (int j = 0; j < n; j++) {
                    transformedCoords[j].set(originalCoords[j]);
                    transformation.transform(transformedCoords[j]);
                }

                // get permutation of subunits and check validity/uniqueness             
                List<Integer> permutation = getPermutation();
  //              System.out.println("Rotation Solver: permutation: " + i + ": " + permutation);
                if (! isValidPermutation(permutation)) {
                    continue;
                }
               
                boolean newPermutation = evaluatePermutation(permutation);
                if (newPermutation) {
                	completeRotationGroup();
                }
                
                // check if all symmetry operations have been found.          
                if (rotations.getOrder() >= maxSymOps) {
                	return;
                }
            }
        }
    }
    
    private void completeRotationGroup() {
    	PermutationGroup g = new PermutationGroup();
    	for (int i = 0; i < rotations.getOrder(); i++) {
    		Rotation s = rotations.getRotation(i);
    		g.addPermutation(s.getPermutation());
    	}
    	g.completeGroup();
    	
 //   	System.out.println("Completing rotation group from: " +symmetryOperations.getSymmetryOperationCount() + " to " + g.getPermutationCount());
    	
    	// the group is complete, nothing to do
    	if (g.getOrder() == rotations.getOrder()) {
    		return;
    	}
    	
 //   	System.out.println("complete group: " +  rotations.getOrder() +"/" + g.getOrder());
    	// try to complete the group
    	for (int i = 0; i < g.getOrder(); i++) {
    		List<Integer> permutation = g.getPermutation(i);
    		if (isValidPermutation(permutation)) {
    			  // perform permutation of subunits
                evaluatePermutation(permutation);
    		}
    	}
    }

	private boolean evaluatePermutation(List<Integer> permutation) {
		// permutate subunits
		for (int j = 0, n = subunits.getSubunitCount(); j < n; j++) {
		    transformedCoords[j].set(originalCoords[permutation.get(j)]);
		}

		int fold = PermutationGroup.getOrder(permutation);
		// get optimal transformation and axisangle by superimposing subunits
		AxisAngle4d axisAngle = new AxisAngle4d();
		Matrix4d transformation = SuperPosition.superposeAtOrigin(transformedCoords, originalCoords, axisAngle);
		double rmsd = SuperPosition.rmsd(transformedCoords, originalCoords);
   //            System.out.println("Complete: " + permutation + " rmsd: " + rmsd);
		// check if it meets criteria and save symmetry operation
		if (rmsd < subunitRmsdThreshold) {
			// transform to original coordinate system
		    combineWithTranslation(transformation);
		    // evaluate superposition of CA traces with GTS score
		    double gts = scorer.calcGtsMinScore(transformation, permutation);
   //                 System.out.println("Complete: " + permutation + " gts: " + gts);
		    if (gts > gtsThreshold) {
		    	double caRmsd = scorer.calcCalphaRMSD(transformation, permutation);
		    	if (caRmsd < 0.0 && !pseudoSymmetryAllowed) {
		    		return false;
		    	}
		    	if (caRmsd > rmsdThreshold) {
		            return false;
		    	}
		    	Rotation symmetryOperation = createSymmetryOperation(permutation, transformation, axisAngle, rmsd, gts, fold);
		        rotations.addRotation(symmetryOperation);
		        return true;
		    }
		}
		return false;
	}

    private List<Double> getAngles() {
        int n = subunits.getSubunitCount();
        // for spherical symmetric cases, n cannot be higher than 60
        if (n % 60 == 0 && isSpherical()) {
           n = Math.min(n, maxOrder);
        }
        List<Double> angles = new ArrayList<Double>(n+1);

        for (int i = 0; i < n; i++) {
 //           if (k == 0 || n % k == 0) { // doesn't find all symmetryOperations
               angles.add(i * 2* Math.PI/n);
 //           }
        }

        // if the maximums symmetry number is odd, add a 2-fold axis. This
        // is required for Dn symmetry, where there are perpendicular
        // 2-fold axes.
        if (n % 2 != 0) {
            angles.add((double)Math.PI);
            Collections.sort(angles);
        }
        
        return angles;
    }
    
    private boolean isSpherical() {
    	MomentsOfInertia m = subunits.getMomentsOfInertia();
    	return m.getSymmetryClass(0.05) == MomentsOfInertia.SymmetryClass.SYMMETRIC;
    }

    private boolean isValidPermutation(List<Integer> permutation) {
        if (permutation.size() == 0) {
 //       	System.out.println("permutation size zero");
            return false;
        }
        // check if permutation is pseudosymmetric
        if (! checkForPseudoSymmetry(permutation)) {
        	return false;
        }
     // get fold and make sure there is only one E (fold=1) permutation
        int fold = PermutationGroup.getOrder(permutation);
        if (rotations.getOrder() > 1 && fold == 1) {
//        	System.out.println("Symop = 1");
            return false;
        }
        if (fold == 0 || subunits.getSubunitCount() % fold != 0) {
  //      	System.out.println(permutation);
  //      	System.out.println("Remove: " + subunits.getSubunitCount() + " / " + fold);
        	return false;
        }
        
        // if this permutation is a duplicate, returns false
        return hashCodes.add(permutation);
    }

    private boolean checkForPseudoSymmetry(List<Integer> permutation) {
    	if (pseudoSymmetryAllowed) {
    		return true;
    	}
    	List<Integer> seqClusterId = subunits.getSequenceClusterIds();
    	for (int i = 0; i < permutation.size(); i++) {
    		int j = permutation.get(i);
    		if (seqClusterId.get(i) != seqClusterId.get(j)) {
    			return false;
    		}
    	}
    	return true;
    }
    /**
     * Adds translational component to rotation matrix
     * @param rotTrans
     * @param rotation
     * @return
     */
    private void combineWithTranslation(Matrix4d rotation) {
        rotation.setTranslation(centroid);
        rotation.mul(rotation, centroidInverse);
    }

    private Rotation createSymmetryOperation(List<Integer> permutation, Matrix4d transformation, AxisAngle4d axisAngle, double rmsd, double gts, int fold) {
        Rotation s = new Rotation();
        s.setPermutation(new ArrayList<Integer>(permutation));
        s.setTransformation(new Matrix4d(transformation));
        s.setAxisAngle(new AxisAngle4d(axisAngle));
        s.setSubunitRmsd(rmsd);
        s.setTraceGtsMin(gts);
        s.setFold(fold);
        return s;
    }

    private void setupDistanceBox() {
        distanceThreshold = calcDistanceThreshold();
        box = new DistanceBox<Integer>(distanceThreshold);

        for (int i = 0; i < originalCoords.length; i++) {
            box.addPoint(originalCoords[i], i);
        }
    }

    private double calcDistanceThreshold() {
        double threshold = Double.MAX_VALUE;
        int n = subunits.getSubunitCount();
        List<Point3d> centers = subunits.getCenters();
        
        for (int i = 0; i < n - 1; i++) {
            Point3d pi = centers.get(i);
            for (int j = i + 1; j < n; j++) {
                Point3d pj = centers.get(j);
                threshold = Math.min(threshold, pi.distanceSquared(pj));
            }
        }
        double distanceThreshold = Math.sqrt(threshold);

 //       System.out.println("Distance threshold: " + distanceThreshold);
        distanceThreshold = Math.max(distanceThreshold, rmsdThreshold);
        
        return distanceThreshold;
    }

    private List<Integer> getPermutation() {
        List<Integer> permutation = new ArrayList<Integer>(transformedCoords.length);
        double sum = 0.0f;

        for (Point3d t: transformedCoords) {
            List<Integer> neighbors = box.getNeighborsWithCache(t);
            int closest = -1;
            double minDist = Double.MAX_VALUE;

           for (int j : neighbors) {
            	double dist = t.distanceSquared(originalCoords[j]);
                if (dist < minDist) {
                    closest = j;
                    minDist = dist;
                } 
            }
            
            sum += minDist;
            if (closest == -1) {
         	   break;
            }
            permutation.add(closest);
        }
        double rmsd = Math.sqrt(sum / transformedCoords.length);

        if (rmsd > distanceThreshold || permutation.size() != transformedCoords.length) {
//        	if (rmsd > distanceThreshold) {
//        		System.out.println("RotationSolver: getPermutation: rmsd: " + rmsd + " threshold: " + distanceThreshold);
//        	}
//        	if (permutation.size() != transformedCoords.length) {
//        		System.out.println("RotationSolver: getPermutation: too short" + permutation.size());
//        	}
            permutation.clear();
            return permutation;
        }
//        if (rmsd > 2*rmsdThreshold || permutation.size() != transformedCoords.length) {
//            permutation.clear();
//            return permutation;
//        }

        // check uniqueness of indices
        Set<Integer> set = new HashSet<Integer>(permutation);
        
        // if size mismatch, clear permutation (its invalid)
        if (set.size() != originalCoords.length) {
  //      	System.out.println("RotationSolver: getPermutation: duplicate members" + set.size());
            permutation.clear();
        }
      
//        System.out.println("RMSD: " + rmsd + " missed: " + Math.sqrt(missed) + " closest: " + missedI + " count: " + count);
 //       System.out.println("P1: " + permutation);
 //       System.out.println("P2: " + p2);
        return permutation;
    }

    private void initialize() {
        scorer = new QuatSuperpositionScorer(subunits);
        
        // translation to centered coordinate system
        centroid = new Vector3d(subunits.getCentroid());

        // translation back to original coordinate system
        Vector3d reverse = new Vector3d(centroid);
        reverse.negate();
        centroidInverse.set(reverse);

        List<Point3d> centers = subunits.getCenters();
        int n = subunits.getSubunitCount();

        originalCoords = new Point3d[n];
        transformedCoords = new Point3d[n];

        for (int i = 0; i < n; i++) {
            originalCoords[i] = centers.get(i);
            transformedCoords[i] = new Point3d();
        }

        setupDistanceBox();
    }

	public void setMaxOrder(int maxOrder) {
		this.maxOrder = maxOrder;
	}
}
