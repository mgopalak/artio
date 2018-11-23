/*
 * Copyright 2015-2018 Real Logic Ltd, Adaptive Financial Consulting Ltd.
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
package uk.co.real_logic.artio.engine;

import io.aeron.Aeron;
import org.agrona.ErrorHandler;
import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import uk.co.real_logic.artio.dictionary.generation.Exceptions;

import static org.agrona.concurrent.AgentRunner.startOnThread;
import static uk.co.real_logic.artio.CommonConfiguration.backoffIdleStrategy;

/**
 * NB: Ensure that a new instance is created for each engine.
 */
public class DefaultEngineScheduler implements EngineScheduler
{
    private AgentRunner framerRunner;
    private AgentRunner archivingRunner;
    private AgentRunner monitoringRunner;
    private RecordingCoordinator recordingCoordinator;

    public void launch(
        final EngineConfiguration configuration,
        final ErrorHandler errorHandler,
        final Agent framer,
        final Agent indexingAgent,
        final Agent monitoringAgent,
        final Agent conductorAgent,
        final RecordingCoordinator recordingCoordinator)
    {
        this.recordingCoordinator = recordingCoordinator;
        if (framerRunner != null)
        {
            EngineScheduler.fail();
        }

        framerRunner = new AgentRunner(
            configuration.framerIdleStrategy(), errorHandler, null, framer);
        archivingRunner = new AgentRunner(
            configuration.archiverIdleStrategy(), errorHandler, null, indexingAgent);

        startOnThread(framerRunner);
        startOnThread(archivingRunner);

        if (monitoringAgent != null)
        {
            monitoringRunner = new AgentRunner(
                backoffIdleStrategy(), errorHandler, null, monitoringAgent);
            startOnThread(monitoringRunner);
        }
    }

    public void close()
    {
        EngineScheduler.awaitRunnerStart(framerRunner);
        EngineScheduler.awaitRunnerStart(archivingRunner);
        EngineScheduler.awaitRunnerStart(monitoringRunner);

        Exceptions.closeAll(framerRunner, archivingRunner, recordingCoordinator, monitoringRunner);
    }

    public void configure(final Aeron.Context aeronContext)
    {
    }
}
