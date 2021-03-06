/*
 * Copyright (c) 2011-2019 Contributors to the Eclipse Foundation
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
 * which is available at https://www.apache.org/licenses/LICENSE-2.0.
 *
 * SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
 */

package io.vertx.core.impl;

import io.vertx.codegen.annotations.Nullable;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.spi.metrics.PoolMetrics;
import io.vertx.core.spi.tracing.VertxTracer;

import java.util.Objects;

/**
 * @author <a href="http://tfox.org">Tim Fox</a>
 */
class WorkerContext extends ContextImpl {

  WorkerContext(VertxInternal vertx, VertxTracer<?, ?> tracer, WorkerPool internalBlockingPool, WorkerPool workerPool, Deployment deployment,
                ClassLoader tccl) {
    super(vertx, tracer, internalBlockingPool, workerPool, deployment, tccl);
  }

  @Override
  <T> void execute(T argument, Handler<T> task) {
    execute(this, orderedTasks, argument, task);
  }

  @Override
  public void execute(Runnable task) {
    execute(this, orderedTasks, task);
  }

  @Override
  public boolean isEventLoopContext() {
    return false;
  }

  // In the case of a worker context, the IO will always be provided on an event loop thread, not a worker thread
  // so we need to execute it on the worker thread
  @Override
  public <T> void dispatchFromIO(T argument, Handler<T> task) {
    if (THREAD_CHECKS) {
      checkEventLoopThread();
    }
    execute(argument, task);
  }

  @Override
  public <T> void dispatch(T argument, Handler<T> task) {
    dispatch(this, argument, task);
  }

  private static <T> void dispatch(AbstractContext ctx, T value, Handler<T> task) {
    if (AbstractContext.context() == ctx) {
      ctx.emit(value, task);
    } else if (ctx.nettyEventLoop().inEventLoop()) {
      ctx.dispatchFromIO(value, task);
    } else {
      ctx.execute(value, task);
    }
  }

  private <T> void execute(ContextInternal ctx, TaskQueue queue, Runnable task) {
    PoolMetrics metrics = workerPool.metrics();
    Object queueMetric = metrics != null ? metrics.submitted() : null;
    queue.execute(() -> {
      Object execMetric = null;
      if (metrics != null) {
        execMetric = metrics.begin(queueMetric);
      }
      try {
        ctx.emit(task);
      } finally {
        if (metrics != null) {
          metrics.end(execMetric, true);
        }
      }
    }, workerPool.executor());
  }

  private <T> void execute(ContextInternal ctx, TaskQueue queue, T value, Handler<T> task) {
    Objects.requireNonNull(task, "Task handler must not be null");
    PoolMetrics metrics = workerPool.metrics();
    Object queueMetric = metrics != null ? metrics.submitted() : null;
    queue.execute(() -> {
      Object execMetric = null;
      if (metrics != null) {
        execMetric = metrics.begin(queueMetric);
      }
      try {
        ctx.emit(value, task);
      } finally {
        if (metrics != null) {
          metrics.end(execMetric, true);
        }
      }
    }, workerPool.executor());
  }

  @Override
  public <T> void schedule(T argument, Handler<T> task) {
    PoolMetrics metrics = workerPool.metrics();
    Object metric = metrics != null ? metrics.submitted() : null;
    orderedTasks.execute(() -> {
      if (metrics != null) {
        metrics.begin(metric);
      }
      try {
        task.handle(argument);
      } finally {
        if (metrics != null) {
          metrics.end(metric, true);
        }
      }
    }, workerPool.executor());
  }

  public ContextInternal duplicate(ContextInternal in) {
    return new Duplicated(this, in);
  }

  static class Duplicated extends ContextImpl.Duplicated<WorkerContext> {

    final TaskQueue orderedTasks = new TaskQueue();

    Duplicated(WorkerContext delegate, ContextInternal other) {
      super(delegate, other);
    }

    @Override
    <T> void execute(T argument, Handler<T> task) {
      delegate.execute(this, orderedTasks, argument, task);
    }

    @Override
    public void execute(Runnable task) {
      delegate.execute(this, orderedTasks, task);
    }

    @Override
    public final <T> Future<T> executeBlockingInternal(Handler<Promise<T>> action) {
      return ContextImpl.executeBlocking(this, action, delegate.internalBlockingPool, delegate.internalOrderedTasks);
    }

    @Override
    public <T> Future<@Nullable T> executeBlocking(Handler<Promise<T>> blockingCodeHandler, boolean ordered) {
      return ContextImpl.executeBlocking(this, blockingCodeHandler, delegate.workerPool, ordered ? orderedTasks : null);
    }

    @Override
    public <T> Future<T> executeBlocking(Handler<Promise<T>> blockingCodeHandler, TaskQueue queue) {
      return ContextImpl.executeBlocking(this, blockingCodeHandler, delegate.workerPool, queue);
    }

    @Override
    public <T> void dispatchFromIO(T argument, Handler<T> task) {
      execute(argument, task);
    }

    @Override
    public <T> void dispatch(T argument, Handler<T> task) {
      delegate.dispatch(this, argument, task);
    }

    @Override
    public boolean isEventLoopContext() {
      return false;
    }

    @Override
    public ContextInternal duplicate(ContextInternal context) {
      return new Duplicated(delegate, context);
    }
  }
}
