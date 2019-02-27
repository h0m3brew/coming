package prophet4j.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import gumtree.spoon.AstComparator;
import gumtree.spoon.diff.Diff;
import gumtree.spoon.diff.operations.DeleteOperation;
import gumtree.spoon.diff.operations.InsertOperation;
import gumtree.spoon.diff.operations.Operation;
import gumtree.spoon.diff.operations.UpdateOperation;
import prophet4j.meta.FeatureStruct.FeatureVector;
import prophet4j.meta.RepairStruct.DiffEntry;
import prophet4j.meta.RepairStruct.Repair;
import prophet4j.meta.RepairType.DiffActionType;
import spoon.reflect.code.CtExpression;
import spoon.reflect.code.CtStatement;
import spoon.reflect.declaration.CtElement;
import spoon.reflect.path.CtPath;
import spoon.reflect.path.CtPathStringBuilder;

// based on pdiffer.cpp, ASTDiffer.cpp
public class CodeDiffer {

    private boolean forDemo;
    private static final Logger logger = LogManager.getLogger(CodeDiffer.class.getName());

    public CodeDiffer(boolean forDemo){
        this.forDemo = forDemo;
    }

    private DiffEntry genDiffEntry(Diff diff, CtElement srcRoot, CtElement dstRoot) throws IndexOutOfBoundsException {
        CtElement ancestor = diff.commonAncestor();
        if (ancestor instanceof CtExpression) {
            while (!(ancestor instanceof CtStatement)){
                ancestor = ancestor.getParent();
            }
        }
        // p & p' in Feature Extraction Algorithm
        // we have to handle the CtPath here because evaluateOn() would be invalid when it meets #subPackage
        CtPath ancestorPath = ancestor.getPath();
        String ancestorPathString = ancestorPath.toString();
        ancestorPathString = ancestorPathString.substring(ancestorPathString.indexOf("#containedType"));
        ancestorPath = new CtPathStringBuilder().fromString(ancestorPathString);
        List<CtElement> srcStmtList = new ArrayList<>(ancestorPath.evaluateOn(srcRoot));
        assert srcStmtList.size() == 1;
        CtElement srcCommonAncestor = srcStmtList.get(0);
//        srcStmtList = getStmtList(srcAncestor);
        List<CtElement> dstStmtList = new ArrayList<>(ancestorPath.evaluateOn(dstRoot));
        assert dstStmtList.size() == 1;
        CtElement dstCommonAncestor = dstStmtList.get(0);
//        dstStmtList = getStmtList(dstAncestor);

        // here is the DiffActionType wrapper for Spoon OperationKind
        List<Operation> operations = diff.getRootOperations();
        boolean existDelete = false;
        boolean existInsert = false;
        boolean existUpdate = false;
//        boolean existMove = false; // it seems not meaningful to us right now
         /* https://github.com/SpoonLabs/gumtree-spoon-ast-diff/issues/55
         In Gumtree, an "Update" operation means that:
         - either the it's a string based element and the string has changed
         - or that only a small fraction of children has changed (to be verified).
         Assume that we have one literal replaced by a method call. This is represented by one deletion and one addition. We can have a higher-level operation "Replace" instead.
          */
        for (Operation operation: operations) {
            if (operation instanceof DeleteOperation) {
                existDelete = true;
            } else if (operation instanceof InsertOperation) {
                existInsert = true;
            } else if (operation instanceof UpdateOperation) {
                existUpdate = true;
//            } else if (operation instanceof MoveOperation) {
//                existMove = true;
            }
        }
        DiffActionType type = DiffActionType.UnknownAction;
        if (existDelete && existInsert || existUpdate) {
            type = DiffActionType.ReplaceAction;
        } else if (existDelete) {
            type = DiffActionType.DeleteAction;
        } else if (existInsert) {
            type = DiffActionType.InsertAction;
        }
        // todo: add more asserts in other places to help debug
        assert type != DiffActionType.UnknownAction;
        return new DiffEntry(type, srcCommonAncestor, dstCommonAncestor);
    }

    private List<FeatureVector> genFeatureVectors(Diff diff, CtElement srcRoot, CtElement dstRoot) {
        List<FeatureVector> featureVectors = new ArrayList<>();
        try {
            FeatureExtractor featureExtractor = new FeatureExtractor();
            DiffEntry diffEntry = genDiffEntry(diff, srcRoot, dstRoot);

            List<Repair> repairs = new ArrayList<>();
            // as RepairGenerator receive diffEntry as parameter, we do not need ErrorLocalizer
            RepairGenerator generator = new RepairGenerator(diffEntry);
            // human repair (at index 0)
            repairs.add(generator.genHumanRepair(diffEntry));
            if (forDemo) {
                // repair candidates (at indexes after 0)
                repairs.addAll(generator.genRepairCandidates());
            }
            for (Repair repair: repairs) {
                assert(repair.actions.size() > 0);
                for (CtElement atom : repair.getCandidateAtoms()) {
//                System.out.println("********");
//                System.out.println(atom);
                    FeatureVector featureVector = featureExtractor.extractFeature(repair, atom).getFeatureVector();
                    featureVectors.add(featureVector);
                }
            }
        } catch (IndexOutOfBoundsException e) {
            logger.log(Level.WARN, "diff.commonAncestor() returns null value");
        }
        return featureVectors;
    }

    public List<FeatureVector> func4Demo(File file0, File file1) throws Exception {
        AstComparator comparator = new AstComparator();
        Diff diff = comparator.compare(file0, file1);
        CtElement srcRoot = comparator.getCtType(file0).getParent();
        CtElement dstRoot = comparator.getCtType(file1).getParent();
//        System.out.println(diff.getRootOperations());
//        System.out.println(diff.commonAncestor());
//        System.out.println(srcRoot);
//        System.out.println(dstRoot);
//        System.out.println("========");
        return genFeatureVectors(diff, srcRoot, dstRoot);
    }

    // for FeatureExtractorTest.java
    public List<FeatureVector> func4Test(String str0, String str1) {
        AstComparator comparator = new AstComparator();
        Diff diff = comparator.compare(str0, str1);
        CtElement srcRoot = comparator.getCtType(str0).getParent();
        CtElement dstRoot = comparator.getCtType(str1).getParent();
//        System.out.println(diff.getRootOperations());
//        System.out.println(diff.commonAncestor());
//        System.out.println(srcRoot);
//        System.out.println(dstRoot);
//        System.out.println("========");
        return genFeatureVectors(diff, srcRoot, dstRoot);
    }
}