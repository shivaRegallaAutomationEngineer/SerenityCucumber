package net.serenitybdd.cucumber.integration.steps;

import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import net.serenitybdd.cucumber.pageActions.LoginPageAction;

import net.thucydides.core.annotations.Steps;

public class LoginSteps {
	@Steps
	LoginPageAction loginpageaction;
	
	@Given("User enter the home page of the application")
	public void user_enter_the_home_page_of_the_application() {
		loginpageaction.openApplication();
	}

	@When("user enter valid admin username and password")
	public void user_enter_valid_admin_username_and_password() {
		loginpageaction.enterCredentails();
	}

	@When("user click on login button")
	public void user_click_on_login_button() {
		loginpageaction.clickLogin();
	}

	@Then("admin user is successfully logged in")
	public void admin_user_is_successfully_logged_in() {
		loginpageaction.verifyLogin();
	}



}
