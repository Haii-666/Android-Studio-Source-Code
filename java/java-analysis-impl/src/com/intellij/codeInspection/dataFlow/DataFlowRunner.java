/*
 * Copyright 2000-2009 JetBrains s.r.o.
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

/*
 * Created by IntelliJ IDEA.
 * User: max
 * Date: Jan 28, 2002
 * Time: 10:16:39 PM
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.instructions.BranchingInstruction;
import com.intellij.codeInspection.dataFlow.instructions.EmptyInstruction;
import com.intellij.codeInspection.dataFlow.instructions.Instruction;
import com.intellij.codeInspection.dataFlow.instructions.MethodCallInstruction;
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory;
import com.intellij.codeInspection.dataFlow.value.DfaVariableValue;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.containers.MultiMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class DataFlowRunner {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.dataFlow.DataFlowRunner");
  private static final Key<Integer> TOO_EXPENSIVE_HASH = Key.create("TOO_EXPENSIVE_HASH");
  public static final long ourTimeLimit = 1000 * 1000 * 1000; //1 sec in nanoseconds

  private Instruction[] myInstructions;
  private final MultiMap<PsiElement, DfaMemoryState> myNestedClosures = new MultiMap<PsiElement, DfaMemoryState>();
  private DfaVariableValue[] myFields;
  private final DfaValueFactory myValueFactory = new DfaValueFactory();

  // Maximum allowed attempts to process instruction. Fail as too complex to process if certain instruction
  // is executed more than this limit times.
  public static final int MAX_STATES_PER_BRANCH = 300;

  protected DataFlowRunner() {
  }

  public DfaValueFactory getFactory() {
    return myValueFactory;
  }

  protected void prepareAnalysis(@NotNull PsiElement psiBlock, Iterable<DfaMemoryState> initialStates) {
  }

  @Nullable
  private Collection<DfaMemoryState> createInitialStates(@NotNull PsiElement psiBlock, InstructionVisitor visitor) {
    PsiClass containingClass = PsiTreeUtil.getParentOfType(psiBlock, PsiClass.class);
    if (containingClass != null && PsiUtil.isLocalOrAnonymousClass(containingClass)) {
      final PsiElement parent = containingClass.getParent();
      final PsiCodeBlock block = DfaPsiUtil.getTopmostBlockInSameClass(parent);
      if ((parent instanceof PsiNewExpression || parent instanceof PsiDeclarationStatement) && block != null) {
        final RunnerResult result = analyzeMethod(block, visitor);
        if (result == RunnerResult.OK) {
          final Collection<DfaMemoryState> closureStates = myNestedClosures.get(DfaPsiUtil.getTopmostBlockInSameClass(psiBlock));
          if (!closureStates.isEmpty()) {
            return closureStates;
          }
        }
        return null;
      }
    }

    return Arrays.asList(createMemoryState());
  }

  public final RunnerResult analyzeMethod(@NotNull PsiElement psiBlock, InstructionVisitor visitor) {
    Collection<DfaMemoryState> initialStates = createInitialStates(psiBlock, visitor);
    return initialStates == null ? RunnerResult.NOT_APPLICABLE : analyzeMethod(psiBlock, visitor, false, initialStates);
  }
  
  public final RunnerResult analyzeMethod(@NotNull PsiElement psiBlock,
                                          InstructionVisitor visitor,
                                          boolean ignoreAssertions,
                                          @NotNull Collection<DfaMemoryState> initialStates) {
    try {
      prepareAnalysis(psiBlock, initialStates);

      final ControlFlow flow = createControlFlowAnalyzer().buildControlFlow(psiBlock, ignoreAssertions);
      if (flow == null) return RunnerResult.NOT_APPLICABLE;

      int endOffset = flow.getInstructionCount();
      myInstructions = flow.getInstructions();
      myFields = flow.getFields();
      myNestedClosures.clear();

      if (LOG.isDebugEnabled()) {
        LOG.debug("Analyzing code block: " + psiBlock.getText());
        for (int i = 0; i < myInstructions.length; i++) {
          Instruction instruction = myInstructions[i];
          LOG.debug(i + ": " + instruction.toString());
        }
      }

      Integer tooExpensiveHash = psiBlock.getUserData(TOO_EXPENSIVE_HASH);
      if (tooExpensiveHash != null && tooExpensiveHash == psiBlock.getText().hashCode()) {
        LOG.debug("Too complex because hasn't changed since being too complex already");
        return RunnerResult.TOO_COMPLEX;
      }

      final ArrayList<DfaInstructionState> queue = new ArrayList<DfaInstructionState>();
      for (final DfaMemoryState initialState : initialStates) {
        queue.add(new DfaInstructionState(myInstructions[0], initialState));
      }

      long timeLimit = ourTimeLimit;
      final boolean unitTestMode = ApplicationManager.getApplication().isUnitTestMode();
      WorkingTimeMeasurer measurer = new WorkingTimeMeasurer(timeLimit);
      int count = 0;
      while (!queue.isEmpty()) {
        if (count % 50 == 0 && !unitTestMode && measurer.isTimeOver()) {
          LOG.debug("Too complex because the analysis took too long");
          psiBlock.putUserData(TOO_EXPENSIVE_HASH, psiBlock.getText().hashCode());
          return RunnerResult.TOO_COMPLEX;
        }
        ProgressManager.checkCanceled();

        DfaInstructionState instructionState = queue.remove(0);
        if (LOG.isDebugEnabled()) {
          LOG.debug(instructionState.toString());
        }
        //System.out.println(instructionState.toString());

        Instruction instruction = instructionState.getInstruction();
        long distance = instructionState.getDistanceFromStart();

        if (instruction instanceof BranchingInstruction) {
          if (!instruction.setMemoryStateProcessed(instructionState.getMemoryState().createCopy())) {
            LOG.debug("Too complex because too many different possible states");
            return RunnerResult.TOO_COMPLEX; // Too complex :(
          }
        }

        DfaInstructionState[] after = acceptInstruction(visitor, instructionState);
        for (DfaInstructionState state : after) {
          Instruction nextInstruction = state.getInstruction();
          if ((!(nextInstruction instanceof BranchingInstruction) || !nextInstruction.isMemoryStateProcessed(state.getMemoryState())) && instruction.getIndex() < endOffset) {
            state.setDistanceFromStart(distance + 1);
            queue.add(state);
          }
        }

        count++;
      }

      psiBlock.putUserData(TOO_EXPENSIVE_HASH, null);
      LOG.debug("Analysis ok");
      return RunnerResult.OK;
    }
    catch (ArrayIndexOutOfBoundsException e) {
      LOG.error(psiBlock.getText(), e); // TODO fix in better times
      return RunnerResult.ABORTED;
    }
    catch (EmptyStackException e) {
      if (LOG.isDebugEnabled()) {
        LOG.error(e); // TODO fix in better times
      }
      return RunnerResult.ABORTED;
    }
  }

  private DfaInstructionState[] acceptInstruction(InstructionVisitor visitor, DfaInstructionState instructionState) {
    Instruction instruction = instructionState.getInstruction();
    if (instruction instanceof MethodCallInstruction) {
      PsiCallExpression anchor = ((MethodCallInstruction)instruction).getCallExpression();
      if (anchor instanceof PsiNewExpression) {
        PsiAnonymousClass anonymousClass = ((PsiNewExpression)anchor).getAnonymousClass();
        if (anonymousClass != null) {
          registerNestedClosures(instructionState, anonymousClass);
        }
      }
    }
    else if (instruction instanceof EmptyInstruction) {
      PsiElement anchor = ((EmptyInstruction)instruction).getAnchor();
      if (anchor instanceof PsiDeclarationStatement) {
        for (PsiElement element : ((PsiDeclarationStatement)anchor).getDeclaredElements()) {
          if (element instanceof PsiClass) {
            registerNestedClosures(instructionState, (PsiClass)element);
          }
        }
      }
    }

    return instruction.accept(this, instructionState.getMemoryState(), visitor);
  }

  private void registerNestedClosures(DfaInstructionState instructionState, PsiClass nestedClass) {
    DfaMemoryStateImpl closureState = createClosureState(instructionState.getMemoryState());
    for (PsiMethod method : nestedClass.getMethods()) {
      PsiCodeBlock body = method.getBody();
      if (body != null) {
        myNestedClosures.putValue(body, closureState);
      }
    }
    for (PsiClassInitializer initializer : nestedClass.getInitializers()) {
      myNestedClosures.putValue(initializer.getBody(), closureState);
    }
    for (PsiField field : nestedClass.getFields()) {
      myNestedClosures.putValue(field, closureState);
    }
  }

  protected ControlFlowAnalyzer createControlFlowAnalyzer() {
    return new ControlFlowAnalyzer(myValueFactory);
  }

  protected DfaMemoryState createMemoryState() {
    return new DfaMemoryStateImpl(myValueFactory);
  }

  public Instruction[] getInstructions() {
    return myInstructions;
  }

  public Instruction getInstruction(int index) {
    return myInstructions[index];
  }

  public DfaVariableValue[] getFields() {
    return myFields;
  }

  public MultiMap<PsiElement, DfaMemoryState> getNestedClosures() {
    return new MultiMap<PsiElement, DfaMemoryState>(myNestedClosures);
  }

  public Pair<Set<Instruction>,Set<Instruction>> getConstConditionalExpressions() {
    Set<Instruction> trueSet = new HashSet<Instruction>();
    Set<Instruction> falseSet = new HashSet<Instruction>();

    for (Instruction instruction : myInstructions) {
      if (instruction instanceof BranchingInstruction) {
        BranchingInstruction branchingInstruction = (BranchingInstruction)instruction;
        if (branchingInstruction.getPsiAnchor() != null && branchingInstruction.isConditionConst()) {
          if (!branchingInstruction.isTrueReachable()) {
            falseSet.add(branchingInstruction);
          }

          if (!branchingInstruction.isFalseReachable()) {
            trueSet.add(branchingInstruction);
          }
        }
      }
    }

    for (Instruction instruction : myInstructions) {
      if (instruction instanceof BranchingInstruction) {
        BranchingInstruction branchingInstruction = (BranchingInstruction)instruction;
        if (branchingInstruction.isTrueReachable()) {
          falseSet.remove(branchingInstruction);
        }
        if (branchingInstruction.isFalseReachable()) {
          trueSet.remove(branchingInstruction);
        }
      }
    }

    return Pair.create(trueSet, falseSet);
  }

  private DfaMemoryStateImpl createClosureState(DfaMemoryState memState) {
    DfaMemoryStateImpl copy = (DfaMemoryStateImpl)memState.createCopy();
    copy.flushFields(getFields());
    Set<DfaVariableValue> vars = new HashSet<DfaVariableValue>(copy.getVariableStates().keySet());
    for (DfaVariableValue value : vars) {
      copy.flushDependencies(value);
    }
    return copy;
  }
}
