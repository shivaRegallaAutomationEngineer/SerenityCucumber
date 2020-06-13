Feature: Login

  Scenario: valid admin login
    Given User enter the home page of the application
    When user enter valid admin username and password
    And  user click on login button
    Then admin user is successfully logged in