@foo
Feature: Basic Arithmetic with several tables
  In order to do my maths homework
  As a maths student
  I want to be able to add sums

  Scenario Outline: Many additions
    Given a calculator I just turned on
    And the previous entries:
      | first | second | operation |
      | 1     | 1      | +         |
      | 2     | 1      | +         |
    When I press +
    And I add <a> and <b>
    And I press +
    Then the result is <c>

  Examples: Single digits
  With just one digit
    | a | b | c  |
    | 1 | 2 | 8  |
    | 2 | 3 | 10 |

  Examples: Double digits
  With more digits than one
    | a  | b  | c  |
    | 10 | 20 | 35 |
    | 20 | 30 | 55 |
    | 25 | 35 | 65 |
