package okhttp3;

import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generate an arbitrary amount of load from an OkHttp client against a server.
 *
 * Use this to reproduce an issue where a large number of sockets are created and closed, putting
 * them into the {@code TIME_WAIT} state.
 */
public class LoadTest {

  private static final int PORT = 8080;
  private static final int QPS = 500;
  private static final int THREADS = 200;
  private static final Random rand = new Random();

  public static void main(String ...args) throws InterruptedException {

    // background thread for calculating rough request throughput

    final AtomicInteger requestCount = new AtomicInteger(0);
    final AtomicLong lastTime = new AtomicLong(System.nanoTime());
    final ScheduledExecutorService timerPool = Executors.newScheduledThreadPool(1);
    Runnable timerRunnable = new Runnable() {
      @Override public void run() {
        long elapsedNanos = System.nanoTime() - lastTime.getAndSet(System.nanoTime());
        int requests = requestCount.getAndSet(0);
        System.out.println("issued " + requests + " requests in " + elapsedNanos + "ns");
      }
    };
    timerPool.scheduleAtFixedRate(timerRunnable, 1, 1, TimeUnit.SECONDS);

    // latch for shutdown

    final AtomicBoolean stopped = new AtomicBoolean(false);
    Runtime.getRuntime().addShutdownHook(new Thread() {
      @Override public void run() {
        System.err.println("shutting down - waiting for worker threads to exit");
        stopped.set(true);
        timerPool.shutdown();

        // allow some time for shutdown
        try {
          Thread.sleep(1_000);
        } catch (InterruptedException ignored) {
        }
      }
    });

    // client - we're just re-issuing the same HEAD requests repeatedly

    final OkHttpClient client = new OkHttpClient.Builder()
        .build();

    final Request request = new Request.Builder()
        .url("http://localhost:" + PORT)
        .head()
        .build();

    // spawn a bunch of threads, each with a given fixed QPS

    System.err.println("starting " + THREADS + " threads");

    final float perThreadSleepMs = THREADS * 1_000 / ((float) QPS);
    System.err.println("per thread sleep time = " + perThreadSleepMs + "ms");

    Thread[] threads = new Thread[THREADS];
    for (int i = 0; i < THREADS; i++) {
      Thread thread = new Thread() {
        @Override public void run() {
          while (true) {
            if (stopped.get()) {
              break;
            }

            try {
              Response response = client.newCall(request).execute();
              //response.body().string(); // consume the body
              response.close();
              if (response.code() == 200) {
                requestCount.incrementAndGet();
              }
              Thread.sleep((long) perThreadSleepMs);
            } catch (Exception ignored) {
              // do nothing
            }
          }
        }
      };

      thread.start();
      threads[i] = thread;

      // pace the starting of the threads
      Thread.sleep((long) ((1000 / THREADS) * rand.nextFloat()));
    }

    // wait for all threads to finish
    System.err.println("waiting for threads to join");
    for (int i = 0; i < THREADS; i++) {
      threads[i].join();
    }
    System.err.println("all workers completed. exiting");
  }
}
