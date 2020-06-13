package cucumber.runtime.formatter;

import com.google.common.collect.Lists;
import cucumber.api.*;
import cucumber.api.event.*;
import cucumber.runtime.io.ResourceLoader;
import gherkin.ast.*;
import gherkin.ast.Scenario;
import gherkin.pickles.Argument;
import gherkin.pickles.PickleCell;
import gherkin.pickles.PickleRow;
import gherkin.pickles.PickleTable;
import io.cucumber.tagexpressions.Expression;
import io.cucumber.tagexpressions.TagExpressionParser;
import net.serenitybdd.core.Serenity;
import net.serenitybdd.core.SerenityListeners;
import net.serenitybdd.core.SerenityReports;
import net.serenitybdd.cucumber.CucumberWithSerenity;
import net.serenitybdd.cucumber.formatting.ScenarioOutlineDescription;
import net.thucydides.core.guice.Injectors;
import net.thucydides.core.model.DataTable;
import net.thucydides.core.model.*;
import net.thucydides.core.model.TestStep;
import net.thucydides.core.model.stacktrace.RootCauseAnalyzer;
import net.thucydides.core.reports.ReportService;
import net.thucydides.core.steps.*;
import net.thucydides.core.util.Inflector;
import net.thucydides.core.webdriver.Configuration;
import net.thucydides.core.webdriver.ThucydidesWebDriverSupport;
import org.jetbrains.annotations.NotNull;
import org.junit.internal.AssumptionViolatedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.*;
import java.util.stream.Collectors;

import static cucumber.runtime.formatter.TaggedScenario.*;
import static java.util.stream.Collectors.toList;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotEmpty;

/**
 * Cucumber Formatter for Serenity.
 *
 * @author L.Carausu (liviu.carausu@gmail.com)
 */
public class SerenityReporter implements Plugin, ConcurrentEventListener {

    private static final String OPEN_PARAM_CHAR = "\uff5f";
    private static final String CLOSE_PARAM_CHAR = "\uff60";

    private static final String SCENARIO_OUTLINE_NOT_KNOWN_YET = "";

    private Configuration systemConfiguration;

    private final List<BaseStepListener> baseStepListeners;

    private final static String FEATURES_ROOT_PATH = "features";

    private FeatureFileLoader featureLoader = new FeatureFileLoader();

    private LineFilters lineFilters;

    private List<Tag> scenarioTags;

    private static final Logger LOGGER = LoggerFactory.getLogger(SerenityReporter.class);

    private ManualScenarioChecker manualScenarioDateChecker;

    private ThreadLocal<ScenarioContext> localContext = ThreadLocal.withInitial(ScenarioContext::new);

    private ScenarioContext getContext() {
        return localContext.get();
    }

    /**
     * Constructor automatically called by cucumber when class is specified as plugin
     * in @CucumberOptions.
     */
    public SerenityReporter() {
        this.systemConfiguration = Injectors.getInjector().getInstance(Configuration.class);
        this.manualScenarioDateChecker = new ManualScenarioChecker(systemConfiguration.getEnvironmentVariables());
        baseStepListeners = Collections.synchronizedList(new ArrayList<>());
        lineFilters = LineFilters.forCurrentContext();
    }

    public SerenityReporter(Configuration systemConfiguration, ResourceLoader resourceLoader) {
        this.systemConfiguration = systemConfiguration;
        this.manualScenarioDateChecker = new ManualScenarioChecker(systemConfiguration.getEnvironmentVariables());
        baseStepListeners = Collections.synchronizedList(new ArrayList<>());
        lineFilters = LineFilters.forCurrentContext();
    }

    private FeaturePathFormatter featurePathFormatter = new FeaturePathFormatter();

    private StepEventBus getStepEventBus(String featurePath) {
        String prefixedPath = featurePathFormatter.featurePathWithPrefixIfNecessary(featurePath);
        return StepEventBus.eventBusFor(prefixedPath);
    }

    private void setStepEventBus(String featurePath) {
        String prefixedPath = featurePathFormatter.featurePathWithPrefixIfNecessary(featurePath);
        StepEventBus.setCurrentBusToEventBusFor(prefixedPath);
    }

    private void initialiseListenersFor(String featurePath) {
        if (getStepEventBus(featurePath).isBaseStepListenerRegistered()) {
            return;
        }
        SerenityListeners listeners = new SerenityListeners(getStepEventBus(featurePath), systemConfiguration);
        baseStepListeners.add(listeners.getBaseStepListener());
    }

    private EventHandler<TestSourceRead> testSourceReadHandler = this::handleTestSourceRead;
    private EventHandler<TestCaseStarted> caseStartedHandler = this::handleTestCaseStarted;
    private EventHandler<TestCaseFinished> caseFinishedHandler = this::handleTestCaseFinished;
    private EventHandler<TestStepStarted> stepStartedHandler = this::handleTestStepStarted;
    private EventHandler<TestStepFinished> stepFinishedHandler = this::handleTestStepFinished;
    private EventHandler<TestRunStarted> runStartedHandler = this::handleTestRunStarted;
    private EventHandler<TestRunFinished> runFinishedHandler = this::handleTestRunFinished;
    private EventHandler<WriteEvent> writeEventHandler = this::handleWrite;

    private void handleTestRunStarted(TestRunStarted event) {
    }

    @Override
    public void setEventPublisher(EventPublisher publisher) {
        publisher.registerHandlerFor(TestSourceRead.class, testSourceReadHandler);
        publisher.registerHandlerFor(TestRunStarted.class, runStartedHandler);
        publisher.registerHandlerFor(TestRunFinished.class, runFinishedHandler);
        publisher.registerHandlerFor(TestCaseStarted.class, caseStartedHandler);
        publisher.registerHandlerFor(TestCaseFinished.class, caseFinishedHandler);
        publisher.registerHandlerFor(TestStepStarted.class, stepStartedHandler);
        publisher.registerHandlerFor(TestStepFinished.class, stepFinishedHandler);
        publisher.registerHandlerFor(WriteEvent.class, writeEventHandler);
    }

    private void handleTestSourceRead(TestSourceRead event) {
        featureLoader.addTestSourceReadEvent(event.uri, event);
        String featurePath = event.uri;

        featureFrom(featurePath).ifPresent(
                feature -> {
                    getContext().setFeatureTags(feature.getTags());

                    resetEventBusFor(featurePath);
                    initialiseListenersFor(featurePath);
                    configureDriver(feature, featurePath);

                    Story userStory = userStoryFrom(feature, relativeUriFrom(event.uri));

                    getStepEventBus(event.uri).testSuiteStarted(userStory);

                }
        );
    }

    private void resetEventBusFor(String featurePath) {
        StepEventBus.clearEventBusFor(featurePath);
    }

    private String relativeUriFrom(String fullPathUri) {
        String featuresRoot = File.separatorChar + FEATURES_ROOT_PATH + File.separatorChar;
        if (fullPathUri.contains(featuresRoot)) {
            return fullPathUri.substring(fullPathUri.lastIndexOf(featuresRoot) + FEATURES_ROOT_PATH.length() + 2);
        } else {
            return fullPathUri;
        }
    }

    private Optional<Feature> featureFrom(String featureFileUri) {

        String defaultFeatureId = new File(featureFileUri).getName().replace(".feature", "");
        String defaultFeatureName = Inflector.getInstance().humanize(defaultFeatureId);

        parseGherkinIn(featureFileUri);

        if (isEmpty(featureLoader.getFeatureName(featureFileUri))) {
            return Optional.empty();
        }

        Feature feature = featureLoader.getFeature(featureFileUri);
        if (feature.getName().isEmpty()) {
            feature = featureLoader.featureWithDefaultName(feature, defaultFeatureName);
        }
        return Optional.of(feature);
    }

    private void parseGherkinIn(String featureFileUri) {
        try {
            featureLoader.getFeature(featureFileUri);
        } catch (Throwable ignoreParsingErrors) {
            LOGGER.warn("Could not parse the Gherkin in feature file " + featureFileUri + ": file ignored");
        }
    }

    private Story userStoryFrom(Feature feature, String featureFileUri) {

        Story userStory = Story.withIdAndPath(TestSourcesModel.convertToId(feature.getName()), feature.getName(), featureFileUri).asFeature();

        if (!isEmpty(feature.getDescription())) {
            userStory = userStory.withNarrative(feature.getDescription());
        }
        return userStory;
    }

    private void handleTestCaseStarted(TestCaseStarted event) {

        String featurePath = event.testCase.getUri();
        getContext().currentFeaturePathIs(featurePath);
        setStepEventBus(featurePath);

        String scenarioName = event.testCase.getName();
        TestSourcesModel.AstNode astNode = featureLoader.getAstNode(getContext().currentFeaturePath(), event.testCase.getLine());

        Optional<Feature> currentFeature = featureFrom(featurePath);

        if ((astNode != null) && currentFeature.isPresent()) {
            getContext().setCurrentScenarioDefinitionFrom(astNode);

            //the sources are read in parallel, global current feature cannot be used
            String scenarioId = scenarioIdFrom(currentFeature.get().getName(), TestSourcesModel.convertToId(getContext().currentScenarioDefinition.getName()));
            boolean newScenario = !scenarioId.equals(getContext().getCurrentScenario());
            if (newScenario) {
                configureDriver(currentFeature.get(), getContext().currentFeaturePath());
                if (getContext().isAScenarioOutline()) {
                    getContext().startNewExample();
                    handleExamples(currentFeature.get(),
                            getContext().currentScenarioOutline().getTags(),
                            getContext().currentScenarioOutline().getName(),
                            getContext().currentScenarioOutline().getExamples());
                }
                startOfScenarioLifeCycle(currentFeature.get(), scenarioName, getContext().currentScenarioDefinition, event.testCase.getLine());
                getContext().currentScenario = scenarioIdFrom(currentFeature.get().getName(), TestSourcesModel.convertToId(getContext().currentScenarioDefinition.getName()));
            } else {
                if (getContext().isAScenarioOutline()) {
                    startExample(event.testCase.getLine());
                }
            }
            Background background = TestSourcesModel.getBackgroundForTestCase(astNode);
            if (background != null) {
                handleBackground(background);
            }
        }
    }

    private void handleTestCaseFinished(TestCaseFinished event) {
        if (getContext().examplesAreRunning()) {
            handleResult(event.result);
            finishExample();
        }

        if (event.result.is(Result.Type.FAILED) && noAnnotatedResultIdDefinedFor(event)) {
            getStepEventBus(event.testCase.getUri()).testFailed(event.result.getError());
        } else {
            getStepEventBus(event.testCase.getUri()).testFinished(getContext().examplesAreRunning());
        }

        getContext().clearStepQueue();
    }

    private boolean noAnnotatedResultIdDefinedFor(TestCaseFinished event) {
        BaseStepListener baseStepListener = getStepEventBus(event.testCase.getUri()).getBaseStepListener();
        return (baseStepListener.getTestOutcomes().isEmpty() || (latestOf(baseStepListener.getTestOutcomes()).getAnnotatedResult() == null));
    }

    private TestOutcome latestOf(List<TestOutcome> testOutcomes) {
        return testOutcomes.get(testOutcomes.size() - 1);
    }

    private List<String> createCellList(PickleRow row) {
        List<String> cells = new ArrayList<>();
        for (PickleCell cell : row.getCells()) {
            cells.add(cell.getValue());
        }
        return cells;
    }

    private void handleTestStepStarted(TestStepStarted event) {
        if (!(event.testStep instanceof HookTestStep)) {
            if (event.testStep instanceof PickleStepTestStep) {
                PickleStepTestStep pickleTestStep = (PickleStepTestStep) event.testStep;
                TestSourcesModel.AstNode astNode = featureLoader.getAstNode(getContext().currentFeaturePath(), pickleTestStep.getStepLine());
                if (astNode != null) {
                    Step step = (Step) astNode.node;
                    if (!getContext().isAddingScenarioOutlineSteps()) {
                        getContext().queueStep(step);
                        getContext().queueTestStep(event.testStep);
                    }
                    if (getContext().isAScenarioOutline()) {
                        int lineNumber = event.getTestCase().getLine();
                        getContext().stepEventBus().updateExampleLineNumber(lineNumber);
                    }
                    Step currentStep = getContext().getCurrentStep();
                    String stepTitle = stepTitleFrom(currentStep, pickleTestStep);
                    getContext().stepEventBus().stepStarted(ExecutedStepDescription.withTitle(stepTitle));
                    getContext().stepEventBus().updateCurrentStepTitle(normalized(stepTitle));
                }
            }
        }
    }

    private void handleWrite(WriteEvent event) {
        getContext().stepEventBus().stepStarted(ExecutedStepDescription.withTitle(event.text));
        getContext().stepEventBus().stepFinished();
    }

    private void handleTestStepFinished(TestStepFinished event) {
        if (!(event.testStep instanceof HookTestStep)) {
            handleResult(event.result);
        }
    }

    private void handleTestRunFinished(TestRunFinished event) {
        generateReports();
        assureTestSuiteFinished();
    }

    private ReportService getReportService() {
        return SerenityReports.getReportService(systemConfiguration);
    }

    private void configureDriver(Feature feature, String featurePath) {
        getStepEventBus(featurePath).setUniqueSession(systemConfiguration.shouldUseAUniqueBrowser());
        List<String> tags = getTagNamesFrom(feature.getTags());
        String requestedDriver = getDriverFrom(tags);
        String requestedDriverOptions = getDriverOptionsFrom(tags);
        if (isNotEmpty(requestedDriver)) {
            ThucydidesWebDriverSupport.useDefaultDriver(requestedDriver);
            ThucydidesWebDriverSupport.useDriverOptions(requestedDriverOptions);
        }
    }

    private List<String> getTagNamesFrom(List<Tag> tags) {
        List<String> tagNames = new ArrayList<>();
        for (Tag tag : tags) {
            tagNames.add(tag.getName());
        }
        return tagNames;
    }

    private String getDriverFrom(List<String> tags) {
        String requestedDriver = null;
        for (String tag : tags) {
            if (tag.startsWith("@driver:")) {
                requestedDriver = tag.substring(8);
            }
        }
        return requestedDriver;
    }

    private String getDriverOptionsFrom(List<String> tags) {
        String requestedDriver = null;
        for (String tag : tags) {
            if (tag.startsWith("@driver-options:")) {
                requestedDriver = tag.substring(16);
            }
        }
        return requestedDriver;
    }

    private void handleExamples(Feature currentFeature, List<Tag> scenarioOutlineTags, String id, List<Examples> examplesList) {
        String featureName = currentFeature.getName();
        List<Tag> currentFeatureTags = currentFeature.getTags();
        getContext().doneAddingScenarioOutlineSteps();
        initializeExamples();
        for (Examples examples : examplesList) {
            if (examplesAreNotExcludedByTags(examples, scenarioOutlineTags, currentFeatureTags)
                    && lineFilters.examplesAreNotExcluded(examples, getContext().currentFeaturePath())) {
                List<TableRow> examplesTableRows = examples
                        .getTableBody()
                        .stream()
                        .filter(tableRow -> lineFilters.tableRowIsNotExcludedBy(tableRow, getContext().currentFeaturePath()))
                        .collect(Collectors.toList());
                List<String> headers = getHeadersFrom(examples.getTableHeader());
                List<Map<String, String>> rows = getValuesFrom(examplesTableRows, headers);

                Map<Integer, Integer> lineNumbersOfEachRow = new HashMap<>();

                for (int i = 0; i < examplesTableRows.size(); i++) {
                    TableRow tableRow = examplesTableRows.get(i);
                    lineNumbersOfEachRow.put(i, tableRow.getLocation().getLine());
                    addRow(exampleRows(), headers, tableRow);
                    if (examples.getTags() != null) {
                        exampleTags().put(examplesTableRows.get(i).getLocation().getLine(), examples.getTags());
                    }
                }
                String scenarioId = scenarioIdFrom(featureName, id);
                boolean newScenario = !getContext().hasScenarioId(scenarioId);

                String exampleTableName = trim(examples.getName());
                String exampleTableDescription = trim(examples.getDescription());
                if (newScenario) {
                    getContext().setTable(
                            dataTableFrom(SCENARIO_OUTLINE_NOT_KNOWN_YET,
                                    headers,
                                    rows,
                                    exampleTableName,
                                    exampleTableDescription,
                                    lineNumbersOfEachRow));
                } else {
                    getContext().addTableRows(headers,
                            rows,
                            exampleTableName,
                            exampleTableDescription,
                            lineNumbersOfEachRow);
                }
                getContext().addTableTags(tagsIn(examples));

                getContext().currentScenarioId = scenarioId;
            }
        }
    }

    @NotNull
    private List<TestTag> tagsIn(Examples examples) {
        return examples.getTags().stream().map(tag -> TestTag.withValue(tag.getName().substring(1))).collect(Collectors.toList());
    }

    private boolean examplesAreNotExcludedByTags(Examples examples, List<Tag> scenarioOutlineTags, List<Tag> currentFeatureTags) {
        if (testRunHasFilterTags()) {
            return examplesMatchFilter(examples, scenarioOutlineTags, currentFeatureTags);
        }
        return true;
    }

    private boolean examplesMatchFilter(Examples examples, List<Tag> scenarioOutlineTags, List<Tag> currentFeatureTags) {
        List<Tag> allExampleTags = getExampleAllTags(examples, scenarioOutlineTags, currentFeatureTags);
        List<String> allTagsForAnExampleScenario = allExampleTags.stream().map(Tag::getName).collect(Collectors.toList());
        String TagValuesFromCucumberOptions = getCucumberRuntimeTags().get(0);
        TagExpressionParser parser = new TagExpressionParser();
        Expression expressionNode = parser.parse(TagValuesFromCucumberOptions);
        return expressionNode.evaluate(allTagsForAnExampleScenario);
    }

    private boolean testRunHasFilterTags() {
        List<String> tagFilters = getCucumberRuntimeTags();
        return (tagFilters != null) && tagFilters.size() > 0;
    }

    private List<String> getCucumberRuntimeTags() {
        if (CucumberWithSerenity.currentRuntimeOptions() == null) {
            return new ArrayList<>();
        } else {
            return CucumberWithSerenity.currentRuntimeOptions().getTagFilters();
        }
    }

    private List<Tag> getExampleAllTags(Examples examples, List<Tag> scenarioOutlineTags, List<Tag> currentFeatureTags) {
        List<Tag> exampleTags = examples.getTags();
        List<Tag> allTags = new ArrayList<>();
        if (exampleTags != null)
            allTags.addAll(exampleTags);
        if (scenarioOutlineTags != null)
            allTags.addAll(scenarioOutlineTags);
        if (currentFeatureTags != null)
            allTags.addAll(currentFeatureTags);
        return allTags;
    }

    private List<String> getHeadersFrom(TableRow headerRow) {
        return headerRow.getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
    }

    private List<Map<String, String>> getValuesFrom(List<TableRow> examplesTableRows, List<String> headers) {

        List<Map<String, String>> rows = new ArrayList<>();
        for (int row = 0; row < examplesTableRows.size(); row++) {
            Map<String, String> rowValues = new HashMap<>();
            int column = 0;
            List<String> cells = examplesTableRows.get(row).getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
            for (String cellValue : cells) {
                String columnName = headers.get(column++);
                rowValues.put(columnName, cellValue);
            }
            rows.add(rowValues);
        }
        return rows;
    }

    private void addRow(Map<Integer, Map<String, String>> exampleRows,
                        List<String> headers,
                        TableRow currentTableRow) {
        Map<String, String> row = new LinkedHashMap<>();
        for (int j = 0; j < headers.size(); j++) {
            List<String> cells = currentTableRow.getCells().stream().map(TableCell::getValue).collect(Collectors.toList());
            row.put(headers.get(j), cells.get(j));
        }
        exampleRows().put(currentTableRow.getLocation().getLine(), row);
    }

    private String scenarioIdFrom(String featureId, String scenarioIdOrExampleId) {
        return (featureId != null && scenarioIdOrExampleId != null) ? String.format("%s;%s", featureId, scenarioIdOrExampleId) : "";
    }

    private void initializeExamples() {
        getContext().setExamplesRunning(true);
    }

    private Map<Integer, Map<String, String>> exampleRows() {
        if (getContext().exampleRows == null) {
            getContext().exampleRows = Collections.synchronizedMap(new HashMap<>());
        }
        return getContext().exampleRows;
    }

    private Map<Integer, List<Tag>> exampleTags() {
        if (getContext().exampleTags == null) {
            getContext().exampleTags = Collections.synchronizedMap(new HashMap<>());
        }
        return getContext().exampleTags;
    }

    private DataTable dataTableFrom(String scenarioOutline,
                                    List<String> headers,
                                    List<Map<String, String>> rows,
                                    String name,
                                    String description,
                                    Map<Integer, Integer> lineNumbersOfEachRow) {
        return DataTable.withHeaders(headers)
                .andScenarioOutline(scenarioOutline)
                .andMappedRows(rows, lineNumbersOfEachRow)
                .andTitle(name)
                .andDescription(description)
                .build();
    }

    private DataTable addTableRowsTo(DataTable table, List<String> headers,
                                     List<Map<String, String>> rows,
                                     String name,
                                     String description) {
        table.startNewDataSet(name, description);
        for (Map<String, String> row : rows) {
            table.appendRow(rowValuesFrom(headers, row));
        }
        return table;
    }

    private List<String> rowValuesFrom(List<String> headers, Map<String, String> row) {
        return headers.stream()
                .map(header -> row.get(header))
                .collect(toList());
    }

    private void startOfScenarioLifeCycle(Feature feature, String scenarioName, ScenarioDefinition scenario, Integer currentLine) {

        boolean newScenario = !scenarioIdFrom(TestSourcesModel.convertToId(feature.getName()), TestSourcesModel.convertToId(scenario.getName())).equals(getContext().currentScenario);
        getContext().currentScenario = scenarioIdFrom(TestSourcesModel.convertToId(feature.getName()), TestSourcesModel.convertToId(scenario.getName()));
        if (getContext().examplesAreRunning()) {
            if (newScenario) {
                startScenario(feature, scenario, scenario.getName());
                getContext().stepEventBus().useExamplesFrom(getContext().getTable());
                getContext().stepEventBus().useScenarioOutline(ScenarioOutlineDescription.from(scenario).getDescription());
            } else {
                getContext().stepEventBus().addNewExamplesFrom(getContext().getTable());
            }
            startExample(currentLine);
        } else {
            startScenario(feature, scenario, scenarioName);
        }
    }

    private void startScenario(Feature currentFeature, ScenarioDefinition scenarioDefinition, String scenarioName) {
        getContext().stepEventBus().setTestSource(TestSourceType.TEST_SOURCE_CUCUMBER.getValue());

        getContext().stepEventBus().testStarted(scenarioName,
                scenarioIdFrom(TestSourcesModel.convertToId(currentFeature.getName()), TestSourcesModel.convertToId(scenarioName)));
        getContext().stepEventBus().addDescriptionToCurrentTest(scenarioDefinition.getDescription());
        getContext().stepEventBus().addTagsToCurrentTest(convertCucumberTags(currentFeature.getTags()));

        if (isScenario(scenarioDefinition)) {
            getContext().stepEventBus().addTagsToCurrentTest(convertCucumberTags(((Scenario) scenarioDefinition).getTags()));
        } else if (isScenarioOutline(scenarioDefinition)) {
            getContext().stepEventBus().addTagsToCurrentTest(convertCucumberTags(((ScenarioOutline) scenarioDefinition).getTags()));
        }

        registerFeatureJiraIssues(currentFeature.getTags());
        List<Tag> tags = getTagsOfScenarioDefinition(scenarioDefinition);
        registerScenarioJiraIssues(tags);

        scenarioTags = tagsForScenario(scenarioDefinition);
        updateResultFromTags(scenarioTags);
    }

    private List<Tag> tagsForScenario(ScenarioDefinition scenarioDefinition) {
        List<Tag> scenarioTags = new ArrayList<>(getContext().featureTags);
        scenarioTags.addAll(getTagsOfScenarioDefinition(scenarioDefinition));
        return scenarioTags;
    }


    private boolean isScenario(ScenarioDefinition scenarioDefinition) {
        return scenarioDefinition instanceof Scenario;
    }

    private boolean isScenarioOutline(ScenarioDefinition scenarioDefinition) {
        return scenarioDefinition instanceof ScenarioOutline;
    }

    private List<Tag> getTagsOfScenarioDefinition(ScenarioDefinition scenarioDefinition) {
        List<Tag> tags = new ArrayList<>();
        if (isScenario(scenarioDefinition)) {
            tags = ((Scenario) scenarioDefinition).getTags();
        } else if (isScenarioOutline(scenarioDefinition)) {
            tags = ((ScenarioOutline) scenarioDefinition).getTags();
        }
        return tags;
    }

    private void registerFeatureJiraIssues(List<Tag> tags) {
        List<String> issues = extractJiraIssueTags(tags);
        if (!issues.isEmpty()) {
            getContext().stepEventBus().addIssuesToCurrentStory(issues);
        }
    }

    private void registerScenarioJiraIssues(List<Tag> tags) {
        List<String> issues = extractJiraIssueTags(tags);
        if (!issues.isEmpty()) {
            getContext().stepEventBus().addIssuesToCurrentTest(issues);
        }
    }

    private List<TestTag> convertCucumberTags(List<Tag> cucumberTags) {

        cucumberTags = completeManualTagsIn(cucumberTags);

        return cucumberTags.stream()
                .map(tag -> TestTag.withValue(tag.getName().substring(1)))
                .collect(toList());
    }

    private List<Tag> completeManualTagsIn(List<Tag> cucumberTags) {
        if (unqualifiedManualTag(cucumberTags).isPresent() && doesNotContainResultTag(cucumberTags)) {
            List<Tag> updatedTags = Lists.newArrayList(cucumberTags);
            updatedTags.add(new Tag(unqualifiedManualTag(cucumberTags).get().getLocation(), "@manual:pending"));
            return updatedTags;
        } else {
            return cucumberTags;
        }
    }

    private boolean doesNotContainResultTag(List<Tag> tags) {
        return !tags.stream().noneMatch(tag -> tag.getName().startsWith("@manual:"));
    }

    private Optional<Tag> unqualifiedManualTag(List<Tag> tags) {
        return tags.stream().filter(tag -> tag.getName().equalsIgnoreCase("@manual")).findFirst();
    }

    private List<String> extractJiraIssueTags(List<Tag> cucumberTags) {
        List<String> issues = new ArrayList<>();
        for (Tag tag : cucumberTags) {
            if (tag.getName().startsWith("@issue:")) {
                String tagIssueValue = tag.getName().substring("@issue:".length());
                issues.add(tagIssueValue);
            }
            if (tag.getName().startsWith("@issues:")) {
                String tagIssuesValues = tag.getName().substring("@issues:".length());
                issues.addAll(Arrays.asList(tagIssuesValues.split(",")));
            }
        }
        return issues;
    }

    private void startExample(Integer lineNumber) {
        Map<String, String> data = exampleRows().get(lineNumber);
        getContext().stepEventBus().clearStepFailures();
        getContext().stepEventBus().exampleStarted(data);
        if (exampleTags().containsKey(lineNumber)) {
            List<Tag> currentExampleTags = exampleTags().get(lineNumber);
            getContext().stepEventBus().addTagsToCurrentTest(convertCucumberTags(currentExampleTags));
        }
    }

    private void finishExample() {
        getContext().stepEventBus().exampleFinished();
        getContext().exampleCount--;
        if (getContext().exampleCount == 0) {
            getContext().setExamplesRunning(false);
            setTableScenarioOutline();
        } else {
            getContext().setExamplesRunning(true);
        }
    }

    private void setTableScenarioOutline() {
        List<Step> steps = getContext().currentScenarioDefinition.getSteps();
        StringBuffer scenarioOutlineBuffer = new StringBuffer();
        for (Step step : steps) {
            scenarioOutlineBuffer.append(step.getKeyword()).append(step.getText()).append("\n\r");
        }
        String scenarioOutline = scenarioOutlineBuffer.toString();
        if (getContext().getTable() != null) {
            getContext().getTable().setScenarioOutline(scenarioOutline);
        }
    }


    private void handleBackground(Background background) {
        getContext().waitingToProcessBackgroundSteps = true;
        String backgroundName = background.getName();
        if (backgroundName != null) {
            getContext().stepEventBus().setBackgroundTitle(backgroundName);
        }
        String backgroundDescription = background.getDescription();
        if (backgroundDescription == null) {
            backgroundDescription = "";
        }
        getContext().stepEventBus().setBackgroundDescription(backgroundDescription);
    }

    private void assureTestSuiteFinished() {
        getContext().clearStepQueue();
        getContext().clearTestStepQueue();

        Optional.ofNullable(getContext().currentFeaturePath()).ifPresent(
                featurePath -> {
                    getStepEventBus(featurePath).testSuiteFinished();
                    getStepEventBus(featurePath).dropAllListeners();
                    getStepEventBus(featurePath).clear();
                    StepEventBus.clearEventBusFor(featurePath);
                }
        );
        Serenity.done();
        getContext().clearTable();
        getContext().currentScenarioId = null;

    }

    private void handleResult(Result result) {
        Step currentStep = getContext().nextStep();
        cucumber.api.TestStep currentTestStep = getContext().nextTestStep();
        recordStepResult(result, currentStep, currentTestStep);
        if (getContext().noStepsAreQueued()) {
            recordFinalResult();
        }
    }

    private void recordStepResult(Result result, Step currentStep, cucumber.api.TestStep currentTestStep) {

        if (getContext().stepEventBus().currentTestIsSuspended()) {
            getContext().stepEventBus().stepIgnored();
        } else if (Result.Type.PASSED.equals(result.getStatus())) {
            getContext().stepEventBus().stepFinished();
        } else if (Result.Type.FAILED.equals(result.getStatus())) {
            failed(stepTitleFrom(currentStep, currentTestStep), result.getError());
        } else if (Result.Type.SKIPPED.equals(result.getStatus())) {
            getContext().stepEventBus().stepIgnored();
        } else if (Result.Type.PENDING.equals(result.getStatus())) {
            getContext().stepEventBus().stepPending();
        } else if (Result.Type.UNDEFINED.equals(result.getStatus())) {
            getContext().stepEventBus().stepPending();
        }
    }

    private void recordFinalResult() {
        if (getContext().waitingToProcessBackgroundSteps) {
            getContext().waitingToProcessBackgroundSteps = false;
        } else {
            updateResultFromTags(scenarioTags);
        }
    }

    private void updateResultFromTags(List<Tag> scenarioTags) {
        if (isManual(scenarioTags)) {
            updateManualResultsFrom(scenarioTags);
        } else if (isPending(scenarioTags)) {
            getContext().stepEventBus().testPending();
        } else if (isSkippedOrWIP(scenarioTags)) {
            getContext().stepEventBus().testSkipped();
            updateCurrentScenarioResultTo(TestResult.SKIPPED);
        } else if (isIgnored(scenarioTags)) {
            getContext().stepEventBus().testIgnored();
            updateCurrentScenarioResultTo(TestResult.IGNORED);
        }
    }

    private void updateManualResultsFrom(List<Tag> scenarioTags) {
        getContext().stepEventBus().testIsManual();

        manualResultDefinedIn(scenarioTags).ifPresent(
                testResult ->
                        UpdateManualScenario.forScenario(getContext().currentScenarioDefinition.getDescription())
                                .inContext(getContext().stepEventBus().getBaseStepListener(), systemConfiguration.getEnvironmentVariables())
                                .updateManualScenario(testResult, scenarioTags)
        );
    }

    private void updateCurrentScenarioResultTo(TestResult pending) {
        getContext().stepEventBus().getBaseStepListener().overrideResultTo(pending);
    }

    private void failed(String stepTitle, Throwable cause) {
        if (!errorOrFailureRecordedForStep(stepTitle, cause)) {
            if (!isEmpty(stepTitle)) {
                getContext().stepEventBus().updateCurrentStepTitle(stepTitle);
            }
            Throwable rootCause = new RootCauseAnalyzer(cause).getRootCause().toException();
            if (isAssumptionFailure(rootCause)) {
                getContext().stepEventBus().assumptionViolated(rootCause.getMessage());
            } else {
                getContext().stepEventBus().stepFailed(new StepFailure(ExecutedStepDescription.withTitle(normalized(currentStepTitle())), rootCause));
            }
        }
    }

    private String currentStepTitle() {
        return getContext().stepEventBus().getCurrentStep().isPresent()
                ? getContext().stepEventBus().getCurrentStep().get().getDescription() : "";
    }

    private boolean errorOrFailureRecordedForStep(String stepTitle, Throwable cause) {
        if (!latestTestOutcome().isPresent()) {
            return false;
        }
        if (!latestTestOutcome().get().testStepWithDescription(stepTitle).isPresent()) {
            return false;
        }
        Optional<TestStep> matchingTestStep = latestTestOutcome().get().testStepWithDescription(stepTitle);
        if (matchingTestStep.isPresent() && matchingTestStep.get().getException() != null) {
            return (matchingTestStep.get().getException().getOriginalCause() == cause);
        }

        return false;
    }

    private Optional<TestOutcome> latestTestOutcome() {

        if (!getContext().stepEventBus().isBaseStepListenerRegistered()) {
            return Optional.empty();
        }

        List<TestOutcome> recordedOutcomes = getContext().stepEventBus().getBaseStepListener().getTestOutcomes();
        return (recordedOutcomes.isEmpty()) ? Optional.empty()
                : Optional.of(recordedOutcomes.get(recordedOutcomes.size() - 1));
    }

    private boolean isAssumptionFailure(Throwable rootCause) {
        return (AssumptionViolatedException.class.isAssignableFrom(rootCause.getClass()));
    }

    private String stepTitleFrom(Step currentStep, cucumber.api.TestStep testStep) {
        if (currentStep != null && testStep instanceof PickleStepTestStep)
            return currentStep.getKeyword()
                    + ((PickleStepTestStep) testStep).getPickleStep().getText()
                    + embeddedTableDataIn((PickleStepTestStep) testStep);
        return "";
    }

    private String embeddedTableDataIn(PickleStepTestStep currentStep) {
        if (!currentStep.getStepArgument().isEmpty()) {
            Argument argument = currentStep.getStepArgument().get(0);
            if (argument instanceof PickleTable) {
                List<Map<String, Object>> rowList = new ArrayList<Map<String, Object>>();
                for (PickleRow row : ((PickleTable) argument).getRows()) {
                    Map<String, Object> rowMap = new HashMap<String, Object>();
                    rowMap.put("cells", createCellList(row));
                    rowList.add(rowMap);
                }
                return convertToTextTable(rowList);
            }
        }
        return "";
    }

    private String convertToTextTable(List<Map<String, Object>> rows) {
        StringBuilder textTable = new StringBuilder();
        textTable.append(System.lineSeparator());
        for (Map<String, Object> row : rows) {
            textTable.append("|");
            for (String cell : (List<String>) row.get("cells")) {
                textTable.append(" ");
                textTable.append(cell);
                textTable.append(" |");
            }
            if (row != rows.get(rows.size() - 1)) {
                textTable.append(System.lineSeparator());
            }
        }
        return textTable.toString();
    }

    private void generateReports() {
        getReportService().generateReportsFor(getAllTestOutcomes());
    }

    public List<TestOutcome> getAllTestOutcomes() {
        return baseStepListeners.stream().map(BaseStepListener::getTestOutcomes).flatMap(List::stream)
                .collect(Collectors.toList());
    }

    private String normalized(String value) {
        return value.replaceAll(OPEN_PARAM_CHAR, "{").replaceAll(CLOSE_PARAM_CHAR, "}");
    }

    private String trim(String stringToBeTrimmed) {
        return (stringToBeTrimmed == null) ? null : stringToBeTrimmed.trim();
    }
}
