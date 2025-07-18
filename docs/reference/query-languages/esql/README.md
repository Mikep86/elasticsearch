The ES|QL documentation is composed of static content and generated content.
The static content exists in this directory and can be edited by hand.
However, the subdirectories `_snippets`, `images` and `kibana` contain a mix
of static and generated content, and so updating them is a bit more involved.

## Static content

The root `esql` directory and the following two subdirectories contain static content:
* `commands` - contains the static content for the ES|QL commands.
  This content will typically contain mostly `include` directives for content in the `_snippets` or `images` directories.
* `functions-operators` - contains the static content for the ES|QL functions and operators.
  Again this will contain mostly `include` directives for content in the `_snippets` or `images` directories.

## Mixed static and generated content

Generated content is created by running the ESQL tests in the `x-pack/plugin/esql` module.
It will be written into three subdirectories of the `esql` directory:

### _snippets

In `_snippets` there are files that can be included within other files using the
[File Inclusion](https://elastic.github.io/docs-builder/syntax/file_inclusion/)
feature of the Elastic Docs V3 system.
Most, but not all, files in this directory are generated.
In particular the directories `_snippets/functions/*` and `_snippets/operators/*`
contain subdirectories that are mostly generated:

* `description` - description of each function scraped from `@FunctionInfo#description`
* `examples` - examples of each function scraped from `@FunctionInfo#examples`
* `parameters` - description of each function's parameters scraped from `@Param`
* `signature` - railroad diagram of the syntax to invoke each function
* `types` - a table of each combination of support type for each parameter. These are generated from tests.
* `layout` - a fully generated description for each function

Most functions can use the generated docs generated in the `layout` directory.
If we need something more custom for the function we can make a file in this
directory that can `include` any parts of the files above.

To regenerate the files for a function run its tests using gradle.
For example to generate docs for the `CASE` function:
```
./gradlew :x-pack:plugin:esql:test -Dtests.class='CaseTests'
```

To regenerate the files for all functions run all of ESQL's tests using gradle:
```
./gradlew :x-pack:plugin:esql:test
```

#### Lists

The `_snippets/lists` directory contains re-usable content for lists of commands, functions or operators.
Whenever adding a command, function or operator, you usually need to add it to one of these lists.
The lists should also match natural groupings of the commands, functions or operators.
For example, when adding an aggregation function, add to the `aggregation-functions.md` file.

#### Commands

The `_snippets/commands` directory contains the content for the ES|QL commands.
There are two subdirectories, one static and one generated:
* `layout` - contains the static content for the ES|QL commands.
  The files in this directory are the main content for the documentation for the commands.
  They are not generated, and so this is the primary place to edit the content, or add new commands.
* `examples` - contains the generated content for the ES|QL commands.
  The files in this directory are generated from the test `CommandDocsTests` in the `x-pack/plugin/esql` module.
  The structure of the subdirectories mimics the csv-spec files and test tags used in the tests.

Including generated examples in the command documentation is done by using the include directive.

### images

The `images` directory contains `functions` and `operators` subdirectories with
the `*.svg` files used to describe the syntax of each function or operator.
These are all generated by the same tests that generate the functions and operators docs above.

### kibana

The `kibana` directory contains `definition` and `docs` subdirectories that are generated:

* `kibana/definition` - function definitions for kibana's ESQL editor
* `kibana/docs` - the inline docs for kibana

These are also generated as part of the unit tests described above.

## Under the hood

There are three overlapping mechanisms for generating the content:
* The `AbstractFunctionTestCase` class generates the content for all the functions and most operators.
  This class makes use of the `DocsV3Support` class to generate the content.
  It uses the `@FunctionInfo` and `@Param` annotations on function and operator classes to know what content should be generated.
  All tests that extend this class will automatically generate the content for the functions they test.
* Some operators do not have a clear class or test class, and so the content is generated by custom
  tests that do not extend the `AbstractOperatorTestCase` class. See, for example, operators such as `Cast ::`,
  which uses `CastOperatorTests` to call directly into the `DocsV3Support` class to generate the content.
* Commands do not have dedicated classes or test classes with annotation that can be used.
  For this reason, the command documentation is generated by the `CommandDocsTests` class.
  Currently, this only covers tested examples used in the documentation, and all other commands
  content is static.
  Since there are no annotations to mark which examples to use, the command documentation
  relies on the docs author providing the knowledge of which examples to use by creating subdirectories
  and examples files that match the csv-spec files and tags to include.

To help differentiate between the static and generated content, the generated content is prefixed with a comment:
```
% This is generated by ESQL's AbstractFunctionTestCase. Do no edit it. See ../README.md for how to regenerate it.
```

## Version differentiation in Docs V3

> [!IMPORTANT]
> Starting with 9.0, we no longer publish separate documentation branches for every minor release (`9.0`, `9.1`, `9.2`, etc.). 
> This means there won't be a different page for `9.1`, `9.2`, and so on. Instead, all changes landing in subsequent minor releases **will appear on the same page**.

Because we now publish just one docs set off of the `main` branch, we use the [`applies_to` metadata](https://elastic.github.io/docs-builder/syntax/applies/) to differentiate features and their availability across different versions. This is a [cumulative approach](https://elastic.github.io/docs-builder/contribute/#cumulative-docs): instead of creating separate pages for each product and release, we update a **single page** with product- and version-specific details over time.

`applies_to` allows us to clearly communicate when features are introduced, when they transition from preview to GA, and which versions support specific functionality.

This metadata accepts a lifecycle and an optional version.

### Functions and operators

Use the `@FunctionAppliesTo` annotation within the `@FunctionInfo` annotation on function and operator classes to specify the lifecycle and version for functions and operators.

For example, to indicate that a function is in technical preview and applies to version 9.0.0, you would use:

```java
@FunctionInfo(
    returnType = "boolean",
    appliesTo = {
        @FunctionAppliesTo(lifeCycle = FunctionAppliesToLifecycle.PREVIEW, version = "9.0.0")
    },
    ...
)
```

When a feature evolves from preview in `9.0` to GA in `9.2`, add a new entry alongside the existing preview entry and remove the `preview = true` boolean:

```java
@FunctionInfo(
    returnType = "boolean",
    preview = false, //  the preview boolean can be removed (or flipped to false) when the function becomes GA
    appliesTo = {
        @FunctionAppliesTo(lifeCycle = FunctionAppliesToLifecycle.PREVIEW, version = "9.0.0"),
        @FunctionAppliesTo(lifeCycle = FunctionAppliesToLifecycle.GA, version = "9.2.0")
    },
    ...
)
```

We updated [`DocsV3Support.java`](https://github.com/elastic/elasticsearch/blob/main/x-pack/plugin/esql/src/test/java/org/elasticsearch/xpack/esql/expression/function/DocsV3Support.java) to generate the `applies_to` metadata correctly for functions and operators.

### Inline `applies_to` metadata

Use [inline annotations](https://elastic.github.io/docs-builder/syntax/applies/#inline-annotations) to specify `applies_to` metadata in descriptions, parameter lists, etc.

For example, the second item in this list is in technical preview as of version 9.2:

```markdown
- Item 1
- Item 2 {applies_to}`stack: preview 9.2.`
```

### Key rules

1. **Use the `preview = true` boolean** for any tech preview feature - this is required for the Kibana inline docs
   - **Remove `preview = true`** only when the feature becomes GA on serverless and is _definitely_ going GA in the next minor release
2. **Never delete `appliesTo` entries** - only add new ones as features evolve from preview to GA
3. **Use specific versions** (`9.0.0`, `9.1.0`) when known, or just `PREVIEW` without a version if timing is uncertain
4. **Add `applies_to` to examples** where necessary

> [!IMPORTANT]
> We don't use `applies_to` in the legacy asciidoc system for 8.x and earlier versions.

### Supported lifecycles

- `PREVIEW` - Feature is in technical preview
- `GA` - Feature is generally available  
- `DEPRECATED` - Feature is deprecated and will be removed in a future release
- `UNAVAILABLE` - Feature is not available in the current version, but may be available in future releases

> [!NOTE] 
> Unreleased version information is automatically sanitized in the docs build output. For example, say you specify `preview 9.3.0`: 
> - Before `9.3.0` is released, the live documentation will display "Planned for a future release" instead of the specific version number. 
>  - This will be updated automatically when the version is released.

## Tutorials

### Adding a new command

When adding a new command, for example adding the `CHANGE_POINT` command, do the following:
1. Create a new file in the `_snippets/commands/layout` directory with the name of the command, for example `change_point.md`.
2. Ensure to specify what versions the command applies to. See [Version differentiation in Docs V3](#version-differentiation-in-docs-v3) for details. [Example PR](https://github.com/elastic/elasticsearch/pull/130314/files#diff-0ab90b6202c5d9eeea75dc95a7cb71dc4d720230342718bff887816771a5a803R3-R6).
3. Add the content for the command to the file. See other files in this directory for examples.
4. Add the command to the list in `_snippets/lists/processing-commands.md`.
5. Add an include directive to the `commands/processing-commands.md` file to include the new command.
6. Add tested examples to the `_snippets/commands/examples` directory. See below for details.

### Adding examples to commands

When adding tested examples to a command, for example adding an example to the `CHANGE_POINT` command, do the following:
* Make sure you have an example in an appropriate csv-spec file in the `x-pack/plugin/esql/qa/testFixtures/src/main/resources/` directory.
* Make sure the example has a tag that is unique in that file, and matches the intent of the test, or the docs reason for including that test.
* If you only want to show the query, and no results, then do not tag the results table,
  otherwise tag the results table with a tag that has the same name as the query tag, but with the suffix `-result`.
* Create a file with the name of the tag in a subdirectory with the name of the csv-spec file
  in the `_snippets/commands/examples` directory. While you could add the content to that file, it is not necessary, merely that the file exists
* Run the test `CommandDocsTests` in the `x-pack/plugin/esql` module to generate the content.

For example, we tag the following test in change_point.csv-spec:

```
example for docs
required_capability: change_point

// tag::changePointForDocs[]
ROW key=[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25]
| MV_EXPAND key
| EVAL value = CASE(key<13, 0, 42)
| CHANGE_POINT value ON key
| WHERE type IS NOT NULL
// end::changePointForDocs[]
;

// tag::changePointForDocs-result[]
key:integer | value:integer | type:keyword | pvalue:double
13          | 42            | step_change  | 0.0
// end::changePointForDocs-result[]
;
```

Then we create the file `_snippets/commands/examples/change_point.csv-spec/changePointForDocs.md` with the content:
```
This should be overwritten
```

Then we run the test `CommandDocsTests` in the `x-pack/plugin/esql` module to generate the content.

Now the content of the changePointForDocs.md file should have been updated:

```
% This is generated by ESQL's AbstractFunctionTestCase. Do no edit it. See ../README.md for how to regenerate it.

\```esql
ROW key=[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21,22,23,24,25]
| MV_EXPAND key
| EVAL value = CASE(key<13, 0, 42)
| CHANGE_POINT value ON key
| WHERE type IS NOT NULL
\```

| key:integer | value:integer | type:keyword | pvalue:double |
| --- | --- | --- | --- |
| 13 | 42 | step_change | 0.0 |
```

Finally include this file in the `CHANGE_POINT` command file `_snippets/commands/layout/change_point.md`:

```
**Examples**

The following example shows the detection of a step change:

:::{include} ../examples/change_point.csv-spec/changePointForDocs.md
:::
```
