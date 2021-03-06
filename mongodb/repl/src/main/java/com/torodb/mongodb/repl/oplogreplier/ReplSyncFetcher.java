/*
 * ToroDB
 * Copyright © 2014 8Kdata Technology (www.8kdata.com)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package com.torodb.mongodb.repl.oplogreplier;

import com.torodb.core.annotations.TorodbRunnableService;
import com.torodb.core.logging.LoggerFactory;
import com.torodb.core.services.RunnableTorodbService;
import com.torodb.core.supervision.Supervisor;
import com.torodb.core.supervision.SupervisorDecision;
import com.torodb.core.transaction.RollbackException;
import com.torodb.mongodb.repl.oplogreplier.batch.OplogBatch;
import com.torodb.mongodb.repl.oplogreplier.fetcher.OplogFetcher;
import com.torodb.mongowp.commands.oplog.OplogOperation;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.ThreadFactory;

import javax.annotation.concurrent.NotThreadSafe;

@NotThreadSafe
class ReplSyncFetcher extends RunnableTorodbService {

  private final Logger logger;

  private final SyncServiceView callback;
  private final OplogFetcher fetcher;

  private volatile Thread runThread;

  ReplSyncFetcher(
      @TorodbRunnableService ThreadFactory threadFactory,
      SyncServiceView callback,
      OplogFetcher fetcher,
      LoggerFactory lf) {
    super(callback, threadFactory);
    this.logger = lf.apply(this.getClass());
    this.callback = callback;
    this.fetcher = fetcher;
  }

  @Override
  protected Logger getLogger() {
    return logger;
  }

  @Override
  protected String serviceName() {
    return "ToroDB Sync Fetcher";
  }

  @Override
  protected void triggerShutdown() {
    if (runThread != null) {
      runThread.interrupt();
    }
  }

  @Override
  public void runProtected() {
    runThread = Thread.currentThread();
    RollbackReplicationException rollbackEx = null;
    boolean oplogFinished = false;
    try {
      while (rollbackEx == null && isRunning()) {
        try {
          if (callback.shouldPause()) {
            callback.awaitUntilUnpaused();
            continue;
          }

          callback.awaitUntilAllFetchedAreApplied();

          OplogBatch oplogBatch = fetcher.fetch();

          if (oplogBatch.isLastOne()) {
            oplogFinished = true;
            break;
          }

          oplogBatch.getOps().forEach((oplogOp) -> {
            try {
              callback.deliver(oplogOp);
            } catch (InterruptedException ex) {
              Thread.interrupted();
              throw new RollbackException(serviceName() + " interrupted while a "
                  + "message was being to deliver.", ex);
            }
          });

          if (!oplogBatch.isReadyForMore()) {
            logger.warn("There is no source to sync from");
            Thread.sleep(1000);
          }
        } catch (InterruptedException ex) {
          Thread.interrupted();
          logger.info("Restarting fetch process", ex);
        } catch (RollbackReplicationException ex) {
          rollbackEx = ex;
        } catch (RollbackException ignore) {
          logger.info("Retrying after a rollback exception");
        } catch (StopReplicationException ex) {
          throw ex;
        } catch (Throwable ex) {
          throw new StopReplicationException(ex);
        }
      }
      if (rollbackEx != null) {
        logger.debug("Requesting rollback");
        callback.rollback(rollbackEx);
      } else {
        if (oplogFinished) {
          logger.info("Remote oplog finished");
        } else {
          logger.info(serviceName() + " ending by external request");
        }
        callback.fetchFinished();
      }
    } catch (StopReplicationException ex) {
      logger.info(serviceName() + " stopped by self request");
      callback.fetchAborted(ex);
    }
    logger.info(serviceName() + " stopped");
  }

  public static interface SyncServiceView extends Supervisor {

    void deliver(OplogOperation oplogOp) throws InterruptedException;

    void rollback(RollbackReplicationException ex);

    void awaitUntilUnpaused() throws InterruptedException;

    boolean shouldPause();

    public void awaitUntilAllFetchedAreApplied();

    public void fetchFinished();

    public void fetchAborted(Throwable ex);

    @Override
    public default SupervisorDecision onError(Object supervised, Throwable error) {
      fetchAborted(error);
      return SupervisorDecision.STOP;
    }
  }
}
