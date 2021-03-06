/**
 * Twitter Tools
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

package cc.twittertools.download;

import cc.twittertools.corpus.data.HTMLStatusExtractor;

import java.io.Reader;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang.StringEscapeUtils;
import org.apache.log4j.Logger;

import com.google.common.base.Preconditions;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.ning.http.client.ProxyServer;
import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseHeaders;
import com.ning.http.client.HttpResponseStatus;
import com.ning.http.client.Response;
import com.ning.http.client.extra.ThrottleRequestFilter;
import com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProvider;

public class AsyncHTMLStatusBlockCrawler {
  private static final Logger LOG = Logger.getLogger(AsyncHTMLStatusBlockCrawler.class);

    public static final int TWEET_BLOCK_SIZE = 500;
//    public static int MAX_CONNECTIONS = 100;
//    public static int CONNECTION_TIMEOUT = 90000;
//    public static int IDLE_CONNECTION_TIMEOUT = 90000;
//    public static int REQUEST_TIMEOUT = 90000;
//    public static int MAX_RETRY_ATTEMPTS = 500;
//    public static int WAIT_BEFORE_RETRY = 90000;
    
    public static int MAX_CONNECTIONS = 100;
    public static int CONNECTION_TIMEOUT = 10000;
    public static int IDLE_CONNECTION_TIMEOUT = 10000;
    public static int REQUEST_TIMEOUT = 10000;
    public static int MAX_RETRY_ATTEMPTS = 500;
    public static int WAIT_BEFORE_RETRY = 10000;
    public static int AWAIT_TERM_TIMEOUT = 90000;
    
  private static final JsonParser JSON_PARSER = new JsonParser();
  private static final Gson GSON = new Gson();
    
//  private final Timer timer = new Timer(true);
  private final ScheduledExecutorService scheduler;
  private final Reader inputReader;
  private final File output;
  private final File repair;
  private final AsyncHttpClient asyncHttpClient;
  private final boolean noFollow;

  // key = statud id, value = tweet JSON
  private final ConcurrentSkipListMap<Long, String> crawl = new ConcurrentSkipListMap<Long, String>();

  // key = statud id, value = data line
  private final ConcurrentSkipListMap<Long, String> crawl_repair = new ConcurrentSkipListMap<Long, String>();

  private final AtomicInteger connections = new AtomicInteger(0);

  public AsyncHTMLStatusBlockCrawler(File file, String output, String repair,
      boolean noFollow) throws IOException {

    this(new InputStreamReader(new FileInputStream(file)), output, repair, noFollow);
  }
    
  public AsyncHTMLStatusBlockCrawler(Reader input, String output, String repair,
      boolean noFollow) throws IOException {
      
      this(input, output, repair, noFollow, null);
  }

  public AsyncHTMLStatusBlockCrawler(Reader input, String output, String repair,
      boolean noFollow, ProxyServer proxy) throws IOException {
      
      
    scheduler = Executors.newScheduledThreadPool(100);
      
    this.noFollow = noFollow;
      
    // Set the input reader to the one give
    this.inputReader = Preconditions.checkNotNull(input);
      
    // check existence of output's parent directory
    this.output = new File(Preconditions.checkNotNull(output));
    File parent = this.output.getParentFile();
    if (parent != null && !parent.exists()) {
      throw new IOException(output + "'s parent directory does not exist!");
    }

    // check existence of repair's parent directory (or set to null if no
    // repair file specified)
    if (repair != null) {
      this.repair = new File(repair);
      parent = this.repair.getParentFile();
      if (parent != null && !parent.exists()) {
        throw new IOException(repair + "'s parent directory does not exist!");
      }
    } else {
      this.repair = null;
    }

    AsyncHttpClientConfig.Builder configBuilder = new AsyncHttpClientConfig.Builder()
        .addRequestFilter(new ThrottleRequestFilter(MAX_CONNECTIONS))
        .setConnectionTimeoutInMs(CONNECTION_TIMEOUT)
        .setIdleConnectionInPoolTimeoutInMs(IDLE_CONNECTION_TIMEOUT)
        .setRequestTimeoutInMs(REQUEST_TIMEOUT).setMaxRequestRetry(0);
      
    if ( proxy != null ) {
        configBuilder.setProxyServer(proxy);
    }
      
    AsyncHttpClientConfig config = configBuilder.build();
    this.asyncHttpClient = new AsyncHttpClient(config);
  }
    
    public AsyncHttpClient getClient() {
        return this.asyncHttpClient;
    }

  public static String getUrl(long id, String username) {
    Preconditions.checkNotNull(username);
    return String.format("https://twitter.com/%s/status/%d", username, id);
  }

  public void fetch() throws IOException {
    long start = System.currentTimeMillis();
    LOG.info("Processing...");

    int cnt = 0;
    BufferedReader data = null;
    try {
      data = new BufferedReader(inputReader);
      String line;
      while ((line = data.readLine()) != null) {
        try {
          String[] arr = line.split("\t");
          long id = Long.parseLong(arr[0]);
          String username = (arr.length > 1) ? arr[1] : "a";
          String url = getUrl(id, username);

          connections.incrementAndGet();
          crawlURL(url, new TweetFetcherHandler(id, username, url, 0, !this.noFollow, line));

          cnt++;

          if (cnt % TWEET_BLOCK_SIZE == 0) {
            LOG.info(cnt + " requests submitted");
          }
        } catch (NumberFormatException e) { // parseLong
          continue;
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    } finally {
      data.close();
    }

    // Wait for the last requests to complete.
    LOG.info("Waiting for remaining requests (" + connections.get() + ") to finish!");
    for (int i = 0; i < 10; i++) {
      if (connections.get() == 0) {
        break;
      }
      try {
        Thread.sleep(1000);
      } catch (Exception e) {
        e.printStackTrace();
      }
    }
      
      // Wait for the timed requests to complete.
      LOG.info("Waiting for remaining timers to finish!");
      scheduler.shutdown();
      for (int i = 0; i < 10; i++) {
          try {
              scheduler.awaitTermination(AWAIT_TERM_TIMEOUT, TimeUnit.MILLISECONDS);
              break;
          } catch (InterruptedException ie) {
              try {
                  Thread.sleep(1000);
              } catch (Exception e) {
                  e.printStackTrace();
              }
          }
      }

    asyncHttpClient.close();

    long end = System.currentTimeMillis();
    long duration = end - start;
    LOG.info("Total request submitted: " + cnt);
    LOG.info(crawl.size() + " tweets fetched in " + duration + "ms");

    LOG.info("Writing tweets...");
    int written = 0;

    OutputStreamWriter out = new OutputStreamWriter(new GZIPOutputStream(
        new FileOutputStream(output)), "UTF-8");
    for (Map.Entry<Long, String> entry : crawl.entrySet()) {
      written++;
      out.write(entry.getValue() + "\n");
    }
    out.close();

    LOG.info(written + " statuses written.");

    if (this.repair != null) {
      LOG.info("Writing repair data file...");
      written = 0;
      out = new OutputStreamWriter(new FileOutputStream(repair), "UTF-8");
      for (Map.Entry<Long, String> entry : crawl_repair.entrySet()) {
        written++;
        out.write(entry.getValue() + "\n");
      }
      out.close();

      LOG.info(written + " statuses need repair.");
    }

    LOG.info("Done!");
  }

  private class TweetFetcherHandler extends AsyncCompletionHandler<Response> {
    private final long id;
    private final String username;
    private final String url;
    private final int numRetries;
    private final boolean followRedirects;
    private final String line;

    private int httpStatus = -1;

    private HTMLStatusExtractor extractor = new HTMLStatusExtractor();

    public TweetFetcherHandler(long id, String username, String url, int numRetries,
        boolean followRedirects, String line) {
      this.id = id;
      this.username = username;
      this.url = url;
      this.numRetries = numRetries;
      this.followRedirects = followRedirects;
      this.line = line;
    }

    public long getId() {
      return id;
    }

    public String getLine() {
      return line;
    }

    @Override
    public STATE onStatusReceived(HttpResponseStatus responseStatus) throws Exception {
      this.httpStatus = responseStatus.getStatusCode();
      switch (this.httpStatus) {
      case 404:
        LOG.warn("Abandoning missing page: " + url);
        connections.decrementAndGet();
        return STATE.ABORT;

      case 500:
        retry();
        return STATE.ABORT;
      }

      return super.onStatusReceived(responseStatus);
    }

    @Override
    public STATE onHeadersReceived(HttpResponseHeaders headers) throws Exception {
      switch (this.httpStatus) {
      case 301:
      case 302:
        String redirect = headers.getHeaders().getFirstValue("Location");
        if (redirect.contains("protected_redirect=true")) {
          LOG.warn("Abandoning protected account: " + url);
          connections.decrementAndGet();
        } else if (redirect.contains("account/suspended")) {
          LOG.warn("Abandoning suspended account: " + url);
          connections.decrementAndGet();
        } else if (redirect.contains("//status") || redirect.contains("login?redirect_after_login")) {
          LOG.warn("Abandoning deleted account: " + url);
          connections.decrementAndGet();
        } else if (followRedirects) {
          crawlURL(redirect, new TweetFetcherHandler(id, username, redirect, numRetries,
              followRedirects, line));
        } else {
          LOG.warn("Abandoning redirect: " + url + ", " + redirect);
          connections.decrementAndGet();
        }
        return STATE.ABORT;
      }

      return super.onHeadersReceived(headers);
    }

    @Override
    public Response onCompleted(Response response) {
      switch (this.httpStatus) {
      case -1:
      case 301:
      case 302:
      case 404:
      case 500:
        return response;
      }

      // extract embedded JSON
      try {
        String html = response.getResponseBody("UTF-8");

        JsonObject status = extractor.extractTweet(html);

        // save the requested id
        status.addProperty("requested_id", new Long(id));

        crawl.put(id, GSON.toJson(status));
        connections.decrementAndGet();

        return response;
      } catch (IOException e) {
        LOG.warn("Error (" + e + "): " + url);
        retry();
        return response;
      } catch (JsonSyntaxException e) {
        LOG.warn("Unable to parse embedded JSON: " + url);
        retry();
        return response;
      } catch (NullPointerException e) {
        LOG.warn("Unexpected format for embedded JSON: " + url);
        retry();
        return response;
      }
    }

    @Override
    public void onThrowable(Throwable t) {
        LOG.warn("Just thrown! {0}", t);
      retry();
    }

    private void retry() {
        LOG.warn("Retry!");
      if (this.numRetries >= MAX_RETRY_ATTEMPTS) {
        LOG.warn("Abandoning after max retry attempts: " + url);
        crawl_repair.put(id, line);
        connections.decrementAndGet();
        return;
      }

//      timer.schedule(new RetryTask(id, username, url, numRetries + 1, followRedirects),
//          WAIT_BEFORE_RETRY);
//        
        
        scheduler.schedule(new RetryTask(id, username, url, numRetries + 1, followRedirects),
                           WAIT_BEFORE_RETRY, TimeUnit.MILLISECONDS);
    }

    private class RetryTask extends TimerTask {
      private final long id;
      private final String username;
      private final String url;
      private final int numRetries;
      private final boolean followRedirects;

      public RetryTask(long id, String username, String url, int numRetries, boolean followRedirects) {
        this.id = id;
        this.username = username;
        this.url = url;
        this.numRetries = numRetries;
        this.followRedirects = followRedirects;
      }

      public void run() {
        crawlURL(url, new TweetFetcherHandler(id, username, url, numRetries, followRedirects, line));
      }
    }
  }

  private void crawlURL(String url, TweetFetcherHandler handler) {
    try {
      asyncHttpClient.prepareGet(url).addHeader("Accept-Charset", "utf-8")
          .addHeader("Accept-Language", "en-US").execute(handler);
    } catch (IOException e) {
      LOG.warn("Abandoning due to error (" + e + "): " + url);
      crawl_repair.put(handler.getId(), handler.getLine());
      connections.decrementAndGet();
    }
  }

  private static final String DATA_OPTION = "data";
  private static final String OUTPUT_OPTION = "output";
  private static final String REPAIR_OPTION = "repair";
  private static final String NOFOLLOW_OPTION = "noFollow";

  @SuppressWarnings("static-access")
  public static void main(String[] args) throws Exception {
    Options options = new Options();
    options.addOption(OptionBuilder.withArgName("path").hasArg()
        .withDescription("data file with tweet ids").create(DATA_OPTION));
    options.addOption(OptionBuilder.withArgName("path").hasArg()
        .withDescription("output file (*.gz)").create(OUTPUT_OPTION));
    options.addOption(OptionBuilder.withArgName("path").hasArg()
        .withDescription("output repair file (can be used later as a data file)")
        .create(REPAIR_OPTION));
    options.addOption(NOFOLLOW_OPTION, NOFOLLOW_OPTION, false, "don't follow 301 redirects");

    CommandLine cmdline = null;
    CommandLineParser parser = new GnuParser();
    try {
      cmdline = parser.parse(options, args);
    } catch (ParseException exp) {
      System.err.println("Error parsing command line: " + exp.getMessage());
      System.exit(-1);
    }

    if (!cmdline.hasOption(DATA_OPTION) || !cmdline.hasOption(OUTPUT_OPTION)) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp(AsyncHTMLStatusBlockCrawler.class.getName(), options);
      System.exit(-1);
    }

    String data = cmdline.getOptionValue(DATA_OPTION);
    String output = cmdline.getOptionValue(OUTPUT_OPTION);
    String repair = cmdline.getOptionValue(REPAIR_OPTION);
    boolean noFollow = cmdline.hasOption(NOFOLLOW_OPTION);
    new AsyncHTMLStatusBlockCrawler(new File(data), output, repair, noFollow).fetch();
  }
}
