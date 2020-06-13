Feature: Buying things - with tables and failures

  Scenario Outline: Buying lots of widgets
    Given I want to purchase <amount> gizmos
    And a gizmo costs $<cost>
    When I buy said gizmos
    Then I should pay $<total>
    Examples:
      | amount | cost | total |
      | 0      | 10   | 0     |
      | 1      | 10   | 10    |
      | 2      | 11   | 50    |
      | 2      | 0    | 0     |

