// Copyright (C) 2003-2009 by Object Mentor, Inc. All rights reserved.
// Released under the terms of the CPL Common Public License version 1.0.
package fitnesse.responders.run;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import fitnesse.FitNesseContext;
import fitnesse.authentication.SecureOperation;
import fitnesse.authentication.SecureResponder;
import fitnesse.authentication.SecureTestOperation;
import fitnesse.components.TraversalListener;
import fitnesse.html.template.HtmlPage;
import fitnesse.html.template.PageTitle;
import fitnesse.http.Request;
import fitnesse.http.Response;
import fitnesse.reporting.BaseFormatter;
import fitnesse.reporting.CompositeExecutionLog;
import fitnesse.reporting.InteractiveFormatter;
import fitnesse.reporting.PageInProgressFormatter;
import fitnesse.reporting.SuiteHtmlFormatter;
import fitnesse.reporting.TestTextFormatter;
import fitnesse.reporting.history.SuiteHistoryFormatter;
import fitnesse.reporting.history.SuiteXmlReformatter;
import fitnesse.reporting.history.TestXmlFormatter;
import fitnesse.responders.ChunkingResponder;
import fitnesse.responders.WikiImporter;
import fitnesse.responders.WikiImportingResponder;
import fitnesse.responders.WikiImportingTraverser;
import fitnesse.testrunner.MultipleTestsRunner;
import fitnesse.testrunner.PagesByTestSystem;
import fitnesse.testrunner.RunningTestingTracker;
import fitnesse.testrunner.SuiteContentsFinder;
import fitnesse.testrunner.SuiteFilter;
import fitnesse.testsystems.TestSummary;
import fitnesse.testsystems.TestSystemListener;
import fitnesse.wiki.PageCrawler;
import fitnesse.wiki.PageData;
import fitnesse.wiki.PageType;
import fitnesse.wiki.PathParser;
import fitnesse.wiki.UrlPathVariableSource;
import fitnesse.wiki.WikiImportProperty;
import fitnesse.wiki.WikiPage;
import fitnesse.responders.WikiPageActions;
import fitnesse.wiki.WikiPagePath;
import fitnesse.wiki.WikiPageUtil;

import static fitnesse.responders.WikiImportingTraverser.ImportError;
import static fitnesse.wiki.WikiImportProperty.isAutoUpdated;

public class SuiteResponder extends ChunkingResponder implements SecureResponder {
  private final Logger LOG = Logger.getLogger(SuiteResponder.class.getName());

  public static final String TEST_RESULT_FILE_DATE_PATTERN = "yyyyMMddHHmmss";
  private static final String NOT_FILTER_ARG = "excludeSuiteFilter";
  private static final String AND_FILTER_ARG = "runTestsMatchingAllTags";
  private static final String OR_FILTER_ARG_1 = "runTestsMatchingAnyTag";
  private static final String OR_FILTER_ARG_2 = "suiteFilter";

  static final RunningTestingTracker runningTestingTracker = new RunningTestingTracker();

  private final WikiImporter wikiImporter;
  private SuiteHistoryFormatter suiteHistoryFormatter;

  private PageData data;
  private String testRunId;
  private BaseFormatter mainFormatter;
  private volatile boolean isClosed = false;

  private boolean debug = false;
  private boolean remoteDebug = false;
  private boolean includeHtml = true;
  int exitCode;
  private CompositeExecutionLog log;


  public SuiteResponder() {
    this(new WikiImporter());
  }

  public SuiteResponder(WikiImporter wikiImporter) {
    super();
    this.wikiImporter = wikiImporter;
  }

  private boolean isInteractive() {
    return mainFormatter instanceof InteractiveFormatter;
  }

  @Override
  public Response makeResponse(FitNesseContext context, Request request) {
    super.makeResponse(context, request);
    testRunId = runningTestingTracker.generateNextTicket();
    response.addHeader("X-FitNesse-Test-Id", testRunId);
    return response;
  }

  protected void doSending() throws Exception {
    debug |= request.hasInput("debug");
    remoteDebug |= request.hasInput("remote_debug");
    includeHtml |= request.hasInput("includehtml");
    data = page.getData();
    log = new CompositeExecutionLog(page);

    createMainFormatter();

    if (isInteractive()) {
      makeHtml().render(response.getWriter());
    } else {
      doExecuteTests();
    }

    closeHtmlResponse(exitCode);
  }

  public void doExecuteTests() {
    if (WikiImportProperty.isImported(data)) {
      importWikiPages();
    }

    try {
      performExecution();
    } catch (Exception e) {
      // Is this necessary? Or is the exception already handled by stopTestSystem?
      mainFormatter.errorOccurred(e);
      LOG.log(Level.WARNING, "error registered in test system", e);
    }

    exitCode = mainFormatter.getErrorCount();
  }

  public void importWikiPages() {
    if (response.isXmlFormat() || !isAutoUpdated(data != null ? data : page.getData())) {
      return;
    }

    try {
      addToResponse("<span class=\"meta\">Updating imported content...</span><span class=\"meta\">");
      new WikiImportingTraverser(wikiImporter, page).traverse(new TraversalListener<Object>() {
        @Override
        public void process(Object pageOrError) {
          if (pageOrError instanceof ImportError) {
            ImportError error = (ImportError) pageOrError;
            addToResponse(" " + error.toString() + ".");
          }
        }
      });
      addToResponse(" Done.");
      // Refresh data, since it might have changed.
      data = page.getData();
    } catch (IOException e) {
      addToResponse(" Import failed: " + e.toString() + ".");
    } finally {
      addToResponse("</span>");
    }
  }

  private HtmlPage makeHtml() {
    PageCrawler pageCrawler = page.getPageCrawler();
    WikiPagePath fullPath = pageCrawler.getFullPath();
    String fullPathName = PathParser.render(fullPath);
    HtmlPage htmlPage = context.pageFactory.newPage();
    htmlPage.setTitle(getTitle() + ": " + fullPathName);
    htmlPage.setPageTitle(new PageTitle(getTitle(), fullPath, data.getAttribute(PageData.PropertySUITES)));
    htmlPage.setNavTemplate("testNav.vm");
    htmlPage.put("actions", new WikiPageActions(page));
    htmlPage.setMainTemplate(mainTemplate());
    htmlPage.put("testExecutor", new TestExecutor());
    htmlPage.setFooterTemplate("wikiFooter.vm");
    htmlPage.put("headerContent", new WikiPageHeaderRenderer());
    htmlPage.put("footerContent", new WikiPageFooterRenderer());
    htmlPage.setErrorNavTemplate("errorNavigator");
    htmlPage.put("errorNavOnDocumentReady", false);
    htmlPage.put("multipleTestsRun", isMultipleTestsRun());
    WikiImportingResponder.handleImportProperties(htmlPage, page);

    return htmlPage;
  }

  public boolean isDebug() {
    return debug;
  }

  public void setDebug(boolean debug) {
    this.debug = debug;
  }

  public class WikiPageHeaderRenderer {

    public String render() {
      return WikiPageUtil.getHeaderPageHtml(page,request);
    }

  }

  public class WikiPageFooterRenderer {

    public String render() {
        return WikiPageUtil.getFooterPageHtml(page,request);
    }

  }

  public class TestExecutor {
    public void execute() {
        doExecuteTests();
    }
  }

  private boolean isMultipleTestsRun() {
    return PageType.fromWikiPage(page) == PageType.SUITE;
  }

  protected void addFormatters(MultipleTestsRunner runner) {
    runner.addTestSystemListener(mainFormatter);
    if (!request.hasInput("nohistory")) {
      runner.addTestSystemListener(getSuiteHistoryFormatter());
    }
    runner.addTestSystemListener(newTestInProgressFormatter());
    if (context.testSystemListener != null) {
      runner.addTestSystemListener(context.testSystemListener);
    }
  }

  private void createMainFormatter() {
    if (response.isXmlFormat()) {
      mainFormatter = newXmlFormatter();
    } else if (response.isTextFormat()) {
      mainFormatter = newTextFormatter();
    } else {
      mainFormatter = newHtmlFormatter();
    }
  }

  protected String getTitle() {
    return "Test Results";
  }

  protected String mainTemplate() {
    return "testPage";
  }

  BaseFormatter newXmlFormatter() {
    SuiteXmlReformatter xmlFormatter = new SuiteXmlReformatter(context, page, response.getWriter(), getSuiteHistoryFormatter());
    if (includeHtml)
      xmlFormatter.includeHtml();
    if (!isMultipleTestsRun())
      xmlFormatter.includeInstructions();
    return xmlFormatter;
  }

  BaseFormatter newTextFormatter() {
    return new TestTextFormatter(response);
  }

  BaseFormatter newHtmlFormatter() {
    return new SuiteHtmlFormatter(context, page, log) {
      @Override
      protected void writeData(String output) {
        addToResponse(output);
      }
    };
  }

  protected TestSystemListener newTestInProgressFormatter() {
    return new PageInProgressFormatter(context);
  }

  protected void performExecution() throws IOException, InterruptedException {
    MultipleTestsRunner runner = newMultipleTestsRunner(getPagesToRun());
    runningTestingTracker.addStartedProcess(testRunId, runner);
    if (isInteractive()) {
      ((InteractiveFormatter) mainFormatter).setTrackingId(testRunId);
    }
    try {
      runner.executeTestPages();
    } finally {
      runningTestingTracker.removeEndedProcess(testRunId);
      log.publish(context.pageFactory);
    }
  }

  protected List<WikiPage> getPagesToRun() {
    SuiteFilter filter = createSuiteFilter(request, page.getPageCrawler().getFullPath().toString());
    SuiteContentsFinder suiteTestFinder = new SuiteContentsFinder(page, filter, root);
    return suiteTestFinder.getAllPagesToRunForThisSuite();
  }

  protected MultipleTestsRunner newMultipleTestsRunner(List<WikiPage> pages) {
    // Add test url inputs to context's variableSource.
    final PagesByTestSystem pagesByTestSystem = new PagesByTestSystem(pages, context.root,
            new UrlPathVariableSource(context.variableSource, request.getMap()));

    MultipleTestsRunner runner = new MultipleTestsRunner(pagesByTestSystem, context.testSystemFactory);
    runner.setRunInProcess(debug);
    runner.setEnableRemoteDebug(remoteDebug);
    runner.addExecutionLogListener(log);
    addFormatters(runner);

    return runner;
  }

  public SecureOperation getSecureOperation() {
    return new SecureTestOperation();
  }

  public void addToResponse(String output) {
    if (!isClosed()) {
      response.add(output);
    }
  }

  synchronized boolean isClosed() {
    return isClosed;
  }

  synchronized void setClosed() {
    isClosed = true;
  }

  void closeHtmlResponse(int exitCode) {
    if (!isClosed()) {
      setClosed();
      response.closeChunks();
      response.addTrailingHeader("Exit-Code", String.valueOf(exitCode));
      response.closeTrailer();
      response.close();
    }
  }

  public Response getResponse() {
    return response;
  }


  public static SuiteFilter createSuiteFilter(Request request, String suitePath) {
    return new SuiteFilter(getOrTagFilter(request),
            getNotSuiteFilter(request),
            getAndTagFilters(request),
            getSuiteFirstTest(request, suitePath));
  }

  private static String getOrTagFilter(Request request) {
    return request != null ? getOrFilterString(request) : null;
  }

  private static String getOrFilterString(Request request) {
    //request already confirmed not-null
    String orFilterString = null;
    if(request.getInput(OR_FILTER_ARG_1) != null){
      orFilterString = (String) request.getInput(OR_FILTER_ARG_1);
    } else {
      orFilterString = (String) request.getInput(OR_FILTER_ARG_2);
    }
    return orFilterString;
  }

  private static String getNotSuiteFilter(Request request) {
    return request != null ? (String) request.getInput(NOT_FILTER_ARG) : null;
  }

  private static String getAndTagFilters(Request request) {
    return request != null ? (String) request.getInput(AND_FILTER_ARG) : null;
  }


  private static String getSuiteFirstTest(Request request, String suiteName) {
    String startTest = null;
    if (request != null) {
      startTest = (String) request.getInput("firstTest");
    }

    if (startTest != null) {
      if (startTest.indexOf(suiteName) != 0) {
        startTest = suiteName + "." + startTest;
      }
    }

    return startTest;
  }


  public static class HistoryWriterFactory implements TestXmlFormatter.WriterFactory {

    public Writer getWriter(FitNesseContext context, WikiPage page, TestSummary counts, long time) throws IOException {
      File resultPath = new File(makePageHistoryFileName(context, page, counts, time));
      File resultDirectory = new File(resultPath.getParent());
      if (!resultDirectory.exists()) {
        resultDirectory.mkdirs();
      }
      File resultFile = new File(resultDirectory, resultPath.getName());
      return new PrintWriter(resultFile, "UTF-8");
    }
  }

  public static String makePageHistoryFileName(FitNesseContext context, WikiPage page, TestSummary counts, long time) {
    return String.format("%s/%s/%s",
            context.getTestHistoryDirectory(),
            page.getPageCrawler().getFullPath().toString(),
            makeResultFileName(counts, time));
  }

  public static String makeResultFileName(TestSummary summary, long time) {
    SimpleDateFormat format = new SimpleDateFormat(TEST_RESULT_FILE_DATE_PATTERN);
    String datePart = format.format(new Date(time));
    return String.format("%s_%d_%d_%d_%d.xml", datePart, summary.getRight(), summary.getWrong(), summary.getIgnores(), summary.getExceptions());
  }


  public SuiteHistoryFormatter getSuiteHistoryFormatter() {
    if (suiteHistoryFormatter == null) {
      HistoryWriterFactory source = new HistoryWriterFactory();
      suiteHistoryFormatter = new SuiteHistoryFormatter(context, page, source);
    }
    return suiteHistoryFormatter;
  }
}
