package com.github.unidbg.thread;

import com.github.unidbg.AbstractEmulator;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Run-level graph and root-fault ordering contracts. */
public class RunWaitGraphTest {

    @Test
    public void cycleRejectsWholeBatchWithoutPublishingPartialEdges() {
        RunContext run = new RunContext();
        RunWaitGraph graph = run.getWaitGraph();
        RunWaitGraph.WaitNodeId a = node(run, 1L);
        RunWaitGraph.WaitNodeId b = node(run, 2L);
        RunWaitGraph.WaitNodeId c = node(run, 3L);
        graph.addBatch(Collections.singletonList(edge(a, b)));
        long epoch = graph.getGraphEpoch();

        try {
            graph.addBatch(Arrays.asList(edge(b, c), edge(c, a)));
            fail("dependency cycle was accepted");
        } catch (RunWaitGraph.DependencyCycleException expected) {
            assertEquals(epoch, graph.getGraphEpoch());
            assertEquals(1, graph.getEdgesSnapshot().size());
        }
    }

    @Test
    public void rootFaultFreezesAdmissionBeforePublishingTransitiveWaiters() {
        RunContext run = new RunContext();
        NoopTask rootTask = new NoopTask(301);
        GuestThreadIncarnation thread = run.registerGuestThread(301, "fault-root");
        TaskThreadBinding binding = thread.bind(rootTask);
        InvocationRecord root = new InvocationRecord(
                1L, 1L, Thread.currentThread(), rootTask,
                InvocationContext.builder().operation("fault-root").build(),
                run, thread, binding, null);
        RunWaitGraph.WaitNodeId rootNode = RunWaitGraph.WaitNodeId.from(root);
        RunWaitGraph.WaitNodeId direct = node(run, 2L);
        RunWaitGraph.WaitNodeId transitive = node(run, 3L);
        run.getWaitGraph().addBatch(Arrays.asList(
                edge(direct, rootNode), edge(transitive, direct)));

        RootFaultController.RootFaultPublication publication =
                run.getRootFaultController().publishRoot(root,
                        RootFaultController.FaultKind.RUNTIME_INTEGRITY_FAULT,
                        new IllegalStateException("contract fault"));

        assertEquals(RunContext.State.QUARANTINED, run.getState());
        assertFalse(run.acceptsAdmission());
        assertEquals(2, publication.getDependentSnapshot().getDependents().size());
        assertTrue(publication.getDependentSnapshot().getDependents().contains(direct));
        assertTrue(publication.getDependentSnapshot().getDependents().contains(transitive));
        assertEquals(1L, publication.getSequence());
    }

    @Test
    public void terminalRemovesIncomingAndOutgoingEdgesTogether() {
        RunContext run = new RunContext();
        RunWaitGraph graph = run.getWaitGraph();
        RunWaitGraph.WaitNodeId terminal = node(run, 1L);
        RunWaitGraph.WaitNodeId dependency = node(run, 2L);
        RunWaitGraph.WaitNodeId waiter = node(run, 3L);
        RunWaitGraph.WaitNodeId unrelated = node(run, 4L);
        graph.addBatch(Arrays.asList(
                edge(terminal, dependency),
                edge(waiter, terminal),
                edge(unrelated, dependency)));
        long epoch = graph.getGraphEpoch();

        graph.removeForTerminal(terminal);

        assertEquals(epoch + 1L, graph.getGraphEpoch());
        assertEquals(1, graph.getEdgesSnapshot().size());
        assertEquals(unrelated, graph.getEdgesSnapshot().get(0).getWaiter());
    }

    @Test
    public void typedDependenciesRetainTheirExactKindAndQualifier() {
        RunContext run = new RunContext();
        RunWaitGraph.WaitNodeId waiter = node(run, 1L);
        RunWaitGraph.WaitDependency mailbox = RunWaitGraph.WaitDependency
                .callbackMailbox(run.getRunId(), 41L, 7L);
        RunWaitGraph.WaitDependency resource = RunWaitGraph.WaitDependency
                .externalResource(run.getRunId(), 42L, 8L);

        run.getWaitGraph().addBatch(Arrays.asList(
                new RunWaitGraph.WaitEdgeDraft(waiter, mailbox,
                        RunWaitGraph.WaitReason.CALLBACK_MAILBOX),
                new RunWaitGraph.WaitEdgeDraft(waiter, resource,
                        RunWaitGraph.WaitReason.EXTERNAL_RESOURCE)));

        assertEquals(RunWaitGraph.DependencyKind.CALLBACK_MAILBOX,
                run.getWaitGraph().getEdgesSnapshot().get(0).getDependency().getKind());
        assertEquals(7L, run.getWaitGraph().getEdgesSnapshot().get(0)
                .getDependency().getQualifier());
        assertEquals(RunWaitGraph.DependencyKind.EXTERNAL_RESOURCE,
                run.getWaitGraph().getEdgesSnapshot().get(1).getDependency().getKind());
        assertEquals(8L, run.getWaitGraph().getEdgesSnapshot().get(1)
                .getDependency().getQualifier());
    }

    private static RunWaitGraph.WaitNodeId node(RunContext run, long id) {
        return new RunWaitGraph.WaitNodeId(run.getRunId(), id, id);
    }

    private static RunWaitGraph.WaitEdgeDraft edge(
            RunWaitGraph.WaitNodeId waiter, RunWaitGraph.WaitNodeId dependency) {
        return new RunWaitGraph.WaitEdgeDraft(waiter,
                RunWaitGraph.WaitDependency.invocation(dependency),
                RunWaitGraph.WaitReason.INVOCATION_TERMINAL);
    }

    private static final class NoopTask extends ThreadTask {
        private NoopTask(int tid) {
            super(tid, 0x1000);
        }

        @Override
        protected Number runThread(AbstractEmulator<?> emulator) {
            return 0L;
        }

        @Override
        protected String toThreadString() {
            return "run-fault-contract";
        }
    }
}
