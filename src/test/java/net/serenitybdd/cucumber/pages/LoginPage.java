package net.serenitybdd.cucumber.pages;

import org.openqa.selenium.By;

import net.serenitybdd.core.pages.PageObject;


public class LoginPage extends PageObject {

	public void enterUserName() {
		$(By.id("txtUsername")).type("Admin");
		
	}
	
	public void enterPassword() {
		$(By.id("txtPassword")).type("admin123");
	}
	
	public void clickLogin() {
		$(By.id("btnLogin")).click();
	}
	public void verifyLogin() {
	String actualTitle=	getDriver().getTitle();
	System.out.println("title of the page---> " +actualTitle);
	}
	
}
