% This is generated by ESQL's AbstractFunctionTestCase. Do not edit it. See ../README.md for how to regenerate it.

**Example**

```esql
FROM employees
| WHERE first_name LIKE """?b*"""
| KEEP first_name, last_name
```

| first_name:keyword | last_name:keyword |
| --- | --- |
| Ebbe | Callaway |
| Eberhardt | Terkki |


