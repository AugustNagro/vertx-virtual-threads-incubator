package io.vertx.await.impl;

import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * A scheduler that run tasks on virtual threads that can await futures.
 *
 * The scheduler serialize task execution on virtual threads.
 *
 * When a task is submitted, a virtual thread is started to execute the task, this thread will continue
 * executing tasks until the task queue is empty.
 *
 * A task can await a future, when it happens, a new virtual thread is started to continue task execution.
 *
 * When an awaited future is completed, the thread awaiting the future preempts the execution and the current virtual
 * thread executing tasks is stopped.
 */
public class Scheduler implements Executor {

  private static final ThreadFactory threadFactory = Thread.ofVirtual().name("vert.x-virtual-thread-", 0).factory();

  private final LinkedList<Runnable> tasks = new LinkedList<>();
  private Thread current;
  private final ThreadLocal<Boolean> inThread = new ThreadLocal<>();
  private final ReentrantLock lock = new ReentrantLock();

  @Override
  public void execute(Runnable command) {
    Thread toStart;
    lock.lock();
    try {
      tasks.addLast(command);
      if (current != null) {
        return;
      }
      toStart = threadFactory.newThread(this::run);
      current = toStart;
    } finally {
      lock.unlock();
    }
    toStart.start();
  }

  private void run() {
    while (true) {
      Runnable cmd;
      lock.lock();
      try {
        if (current != Thread.currentThread()) {
          break;
        }
        cmd = tasks.poll();
        if (cmd == null) {
          current = null;
          break;
        }
      } finally {
        lock.unlock();
      }
      inThread.set(true);
      try {
        cmd.run();
      } finally {
        inThread.set(false);
      }
    }
  }

  public boolean inThread() {
    return inThread.get() == Boolean.TRUE;
  }

  public Consumer<Runnable> detach() {
    Thread th = Thread.currentThread();
    Thread toStart;
    lock.lock();
    try {
      if (current != th) {
        throw new IllegalStateException();
      }
      if (tasks.size() > 0) {
        toStart = threadFactory.newThread(this::run);
      } else {
        toStart = null;
      }
      current = toStart;
    } finally {
      lock.unlock();
    }
    if (toStart != null) {
      toStart.start();
    }
    return r -> {
      lock.lock();
      try {
        if (current != null) {
          tasks.addFirst(() -> {
            lock.lock();
            try {
              current = th;
            } finally {
              lock.unlock();
            }
            r.run();
          });
          return;
        }
        current = th;
      } finally {
        lock.unlock();
      }
      r.run();
    };
  }
}
