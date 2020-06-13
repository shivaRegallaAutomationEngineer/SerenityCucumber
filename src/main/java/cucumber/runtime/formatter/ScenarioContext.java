package cucumber.runtime.formatter;


import cucumber.api.TestStep;
import gherkin.ast.*;
import net.thucydides.core.model.DataTable;
import net.thucydides.core.model.DataTableRow;
import net.thucydides.core.model.TestTag;
import net.thucydides.core.steps.StepEventBus;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.stream.Collectors.toList;

class ScenarioContext {
    private final Queue<Step> stepQueue = new LinkedList<>();
    private final Queue<cucumber.api.TestStep> testStepQueue = new LinkedList<>();

    private boolean examplesRunning;
    private boolean addingScenarioOutlineSteps = false;
    private DataTable table;

    //keys are line numbers, entries are example rows (key=header, value=rowValue )
    Map<Integer, Map<String, String>> exampleRows;

    //keys are line numbers
    Map<Integer, List<Tag>> exampleTags;

    int exampleCount = 0;

    boolean waitingToProcessBackgroundSteps = false;

    String currentScenarioId;

    ScenarioDefinition currentScenarioDefinition;

    String currentScenario;

    List<Tag> featureTags = new ArrayList<>();

    String currentFeaturePath;

    private FeaturePathFormatter featurePathFormatter = new FeaturePathFormatter();

    public void currentFeaturePathIs(String featurePath) {
        currentFeaturePath = featurePath;
    }

    public ScenarioOutline currentScenarioOutline() {
        return (ScenarioOutline) currentScenarioDefinition;
    }

    public String currentFeaturePath() {
        return currentFeaturePath;
    }

    public Queue<Step> getStepQueue() {
        return stepQueue;
    }

    public boolean examplesAreRunning() {
        return examplesRunning;
    }

    public Map<Integer, Map<String, String>> getExampleRows() {
        return exampleRows;
    }

    public Map<Integer, List<Tag>> getExampleTags() {
        return exampleTags;
    }

    public int getExampleCount() {
        return exampleCount;
    }

    public DataTable getTable() {
        return table;
    }

    public boolean isWaitingToProcessBackgroundSteps() {
        return waitingToProcessBackgroundSteps;
    }

    public String getCurrentScenarioId() {
        return currentScenarioId;
    }

    public ScenarioDefinition getCurrentScenarioDefinition() {
        return currentScenarioDefinition;
    }

    public String getCurrentScenario() {
        return currentScenario;
    }

    public List<Tag> getFeatureTags() {
        return featureTags;
    }

    public boolean isAddingScenarioOutlineSteps() {
        return addingScenarioOutlineSteps;
    }

    public void doneAddingScenarioOutlineSteps() {
        this.addingScenarioOutlineSteps = false;
    }

    public void setFeatureTags(List<Tag> tags) {
        this.featureTags = new ArrayList<>(tags);
    }

    public void setCurrentScenarioDefinitionFrom(TestSourcesModel.AstNode astNode) {
        this.currentScenarioDefinition = TestSourcesModel.getScenarioDefinition(astNode);
    }

    public boolean isAScenarioOutline() {
        return currentScenarioDefinition instanceof ScenarioOutline;
    }

    public void startNewExample() {
        examplesRunning = true;
        addingScenarioOutlineSteps = true;
    }

    public void setExamplesRunning(boolean examplesRunning) {
        this.examplesRunning = examplesRunning;
    }

    public List<Tag> getScenarioTags() {
        if (currentScenarioDefinition instanceof ScenarioOutline) {
            return ((ScenarioOutline) currentScenarioDefinition).getTags();
        } else if (currentScenarioDefinition instanceof Scenario) {
            return ((Scenario) currentScenarioDefinition).getTags();
        } else {
            return new ArrayList<>();
        }
    }

    public String getScenarioName() {
        return currentScenarioDefinition.getName();
    }

    public List<Examples> getScenarioExamples() {
        return ((ScenarioOutline) currentScenarioDefinition).getExamples();
    }

    public void clearStepQueue() {
        stepQueue.clear();
    }

    public void clearTestStepQueue() {
        testStepQueue.clear();
    }

    public void queueStep(Step step) {
        stepQueue.add(step);
    }

    public void queueTestStep(TestStep testStep) {
        testStepQueue.add(testStep);
    }

    public Step getCurrentStep() {
        return stepQueue.peek();
    }

    public Step nextStep() {
        return stepQueue.poll();
    }

    public TestStep nextTestStep() {
        return testStepQueue.poll();
    }

    public boolean noStepsAreQueued() {
        return stepQueue.isEmpty();
    }

    public boolean hasScenarioId(String scenarioId) {
        return (currentScenarioId != null) && (currentScenarioId.equals(scenarioId));
    }

    public void setTable(DataTable table) {
        this.table = table;
        exampleCount = table.getSize();
    }

    public void addTableRows(List<String> headers,
                             List<Map<String, String>> rows,
                             String name,
                             String description,
                             Map<Integer, Integer> lineNumbersOfEachRow) {
        table.startNewDataSet(name, description);

        AtomicInteger rowNumber = new AtomicInteger();
        rows.forEach(
                row -> table.appendRow(newRow(headers, lineNumbersOfEachRow, rowNumber.getAndIncrement(), row))
        );
        table.updateLineNumbers(lineNumbersOfEachRow);
        exampleCount = table.getSize();
    }

    @NotNull
    private DataTableRow newRow(List<String> headers,
                                Map<Integer, Integer> lineNumbersOfEachRow,
                                int rowNumber,
                                Map<String, String> row) {
        return new DataTableRow(
                rowValuesFrom(headers, row),
                lineNumbersOfEachRow.getOrDefault(rowNumber, 0));
    }

    private List<String> rowValuesFrom(List<String> headers, Map<String, String> row) {
        return headers.stream().map(row::get).collect(toList());
    }

    public void addTableTags(List<TestTag> tags) {
        table.addTagsToLatestDataSet(tags);
    }

    public void clearTable() {
        table = null;
    }

    public StepEventBus stepEventBus() {
        String prefixedPath = featurePathFormatter.featurePathWithPrefixIfNecessary(currentFeaturePath());
        return StepEventBus.eventBusFor(prefixedPath);
    }
}

