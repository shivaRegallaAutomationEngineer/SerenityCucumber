package net.serenitybdd.cucumber.pageActions;

import net.serenitybdd.cucumber.pages.LoginPage;
import net.thucydides.core.annotations.Step;

public class LoginPageAction {
	LoginPage loginPage;
	@Step
	public void openApplication() {
		loginPage.open();
	}
	@Step
	public void enterCredentails() {
		loginPage.enterUserName();
		loginPage.enterPassword();
	}
	@Step
	public void clickLogin() {
		loginPage.clickLogin();
	}
	@Step
	public void verifyLogin() {
	       loginPage.verifyLogin();
	}
}
