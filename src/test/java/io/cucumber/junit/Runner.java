package io.cucumber.junit;

import org.junit.runner.RunWith;

import net.serenitybdd.cucumber.CucumberWithSerenity;

@RunWith(CucumberWithSerenity.class)
@CucumberOptions(features="src/test/resources/features/sprint/sprint1.feature"
,glue="net.serenitybdd.cucumber.integration.steps"
		)
public class Runner {

}
