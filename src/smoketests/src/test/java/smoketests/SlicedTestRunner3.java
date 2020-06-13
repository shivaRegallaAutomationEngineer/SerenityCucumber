package smoketests;

import io.cucumber.junit.CucumberOptions;
import io.cucumber.junit.CucumberWithSerenity;
import org.junit.runner.RunWith;

@RunWith(CucumberWithSerenity.class)
@CucumberOptions(glue = "smoketests.stepdefinitions", features="classpath:features")
public class SlicedTestRunner3 {

   /*

   This is test runner is automatically used when the suite is run using the maven profile useTheForks
   See SlicedTestRunner.java or full documentation on https://serenity-bdd.github.io/theserenitybook/0.1.0/serenity-parallel.html

   */


}
