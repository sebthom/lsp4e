/*******************************************************************************
 * Copyright (c) 2025 Vegard IT GmbH and others.
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 *
 * Contributors:
 *  Sebastian Thomschke (Vegard IT GmbH) - initial implementation
 *******************************************************************************/
package org.eclipse.lsp4e.test;

import static org.eclipse.lsp4e.test.utils.TestUtils.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import org.eclipse.core.resources.IFile;
import org.eclipse.lsp4e.LanguageServiceAccessor;
import org.eclipse.lsp4e.test.utils.AbstractTestWithProject;
import org.eclipse.lsp4e.test.utils.TestUtils;
import org.eclipse.lsp4e.tests.mock.MockLanguageServer;
import org.eclipse.lsp4j.jsonrpc.RemoteEndpoint;
import org.eclipse.lsp4j.jsonrpc.json.StreamMessageProducer;
import org.junit.jupiter.api.Test;

/**
 * Regression test for shutdown sequence (related to GH #681): Ensure we can
 * shut down all servers cleanly without interrupting the JSON-RPC producer (no
 * "input stream was closed" / InterruptedIOException).
 */
class ShutdownSequenceTest extends AbstractTestWithProject {

	@Test
	void gracefulShutdownDoesNotInterruptIO() throws Exception {
		// Start a mock LS by opening a file with the test content-type
		IFile file = TestUtils.createUniqueTestFile(project, "");
		TestUtils.openEditor(file);

		waitForAndAssertCondition("MockLanguageServer should be running", 5_000,
				() -> MockLanguageServer.INSTANCE.isRunning());

		// Capture LSP4J logs that indicate shutdown problems
		var log = Logger.getLogger(StreamMessageProducer.class.getName());
		var remoteEndpointLog = Logger.getLogger(RemoteEndpoint.class.getName());
		var messages = Collections.synchronizedList(new ArrayList<String>());
		var sawInterruptedIO = new AtomicBoolean(false);
		var sawSevere = new AtomicBoolean(false);
		Handler handler = new Handler() {
			@Override
			public void publish(LogRecord r) {
				if (r == null)
					return;
				messages.add((r.getLevel() != null ? r.getLevel().getName() + ": " : "") + r.getMessage());
				if (r.getLevel() == Level.SEVERE) {
					sawSevere.set(true);
				}
				if (r.getThrown() instanceof InterruptedIOException) {
					sawInterruptedIO.set(true);
				}
			}

			@Override
			public void flush() {
			}

			@Override
			public void close() throws SecurityException {
			}
		};

		log.addHandler(handler);
		remoteEndpointLog.addHandler(handler);
		Level old = log.getLevel();
		Level oldRemote = remoteEndpointLog.getLevel();
		try {
			log.setLevel(Level.ALL);
			remoteEndpointLog.setLevel(Level.ALL);

			LanguageServiceAccessor.clearStartedServers();

			// Assertions: LS stopped, and no stream-closed/InterruptedIO in logs
			waitForAndAssertCondition("MockLanguageServer should be stopped", 5_000,
					() -> !MockLanguageServer.INSTANCE.isRunning());

			Thread.sleep(2_000);

			boolean hasStreamClosedMsg = messages.stream()
					.anyMatch(m -> m != null && m.toLowerCase().contains("input stream was closed"));
			boolean hasConnReset = messages.stream()
					.anyMatch(m -> m != null && m.toLowerCase().contains("connection reset by peer"));
			assertFalse(hasStreamClosedMsg, "Unexpected 'input stream was closed' during shutdown");
			assertFalse(hasConnReset, "Unexpected 'connection reset by peer' during shutdown");
			assertFalse(sawInterruptedIO.get(), "Unexpected InterruptedIOException during shutdown");
			assertFalse(sawSevere.get(), "Unexpected SEVERE logs during shutdown");
		} finally {
			log.removeHandler(handler);
			remoteEndpointLog.removeHandler(handler);
			log.setLevel(old);
			remoteEndpointLog.setLevel(oldRemote);
		}
	}
}
