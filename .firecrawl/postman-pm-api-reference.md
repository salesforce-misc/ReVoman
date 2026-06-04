For AI agents: a documentation index is available at the root level at /llms.txt and /llms-full.txt. Append /llms.txt to any URL for a page-level index, or .md for the markdown version of any page.

Postman provides JavaScript APIs with the `pm` object, enabling you to test and access request and response data in your test scripts run in the [Postman Sandbox](https://github.com/postmanlabs/postman-sandbox). You can use the `pm` object to access request and response details, write assertions, and access and use variables. You can also use the `pm` object to send HTTP requests and other meta information in the Postman Sandbox.

## Use scripts to access cookies

Use the `pm.cookies` methods in scripts to access and manipulate cookies. To learn more, see [Access cookies in Postman scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-cookies).

## Use scripts with requests and responses

Reference requests and responses with the `pm.request` and `pm.response` objects in scripts. Streaming protocols also return a `pm.message` object. The `pm.info` object contains meta info related to the request and script Use the `pm.sendRequest` method in your scripts to send requests in Postman. To learn more, see [Reference Postman requests in scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-request), [Use scripts to send requests in Postman](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-send-request), [Reference request metadata in scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-info) and [Reference message data in scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-message).

## Use scripts with collections

The `pm.execution` object provides information and context about requests and their responses during a [collection run](https://learning.postman.com/docs/collections/running-collections/intro-to-collection-runs), such as sending requests or which request is running, its position in a collection, and run-related metadata. To learn more, see [Use scripts in collection runs](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-execution).

## Script variables

Access and manipulate different [variable types](https://learning.postman.com/docs/use/send-requests/variables/variables) and scopes in your scripts. To learn more, see [Reference variables in Postman scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-variables).

## Use scripts to define mock responses

The `pm.mock` object provides structured functions for matching requests and sending responses. You can use it to serve responses from your saved Postman examples instead of hard-coding them in your mock server implementation file. To learn more, see [Reference requests and examples in local mock servers](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-mock).

## Use scripts with datasets

The `pm.datasets` function enables you to access and query dataset data in scripts. You can use it to run SQL queries or predefined views to retrieve data when your script runs. This enables your tests and mock server logic to use dynamic, data-driven values instead of hard-coded data. To learn more, see [Manage and use datasets in scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-datasets).

## Use scripts to manage persistent state

The `pm.state` object provides a persistent store for managing data across script executions. You can use it to read, write, and update data. This enables stateful behavior instead of relying on static responses in your mock server implementation file. To learn more, see [Persist state across requests in local mock servers](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-state).

## Use scripts to visualize data

The `pm.visualizer` object enables you to visually represent your API’s request responses with the [Postman Visualizer](https://learning.postman.com/docs/use/send-requests/response-data/visualizer). To learn more, see [Script Postman visualizations](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-visualizer).

## Manage Postman Vault with scripts

Access and manipulate [vault secrets](https://learning.postman.com/docs/use/postman-vault/postman-vault-secrets) in your scripts with the `pm.vault` methods. To learn more, see [Reference vault secrets in Postman scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-vault).

## Test assertions with scripts

Use the `pm.test` and `pm.expect` methods in your scripts to add test specifications and assertions. To learn more, see [Writing tests and assertions in scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-test-expect).

## Import packages into your scripts

The `pm.require` method enables you to import packages from your team’s [Package Library](https://learning.postman.com/docs/tests-and-scripts/write-scripts/packages/package-library) or [external package registries](https://learning.postman.com/docs/tests-and-scripts/write-scripts/packages/external-package-registries) inside scripts in HTTP, gRPC, and GraphQL requests. To learn more, see [Import packages into your scripts](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-require).

![Postman Community.](https://voyager.postman.com/icon/cta-community-icon.svg)

Ask questions, share knowledge, and connect with developers

[Join the Community](https://community.postman.com/?utm_source=website&utm_medium=microsite&utm_campaign=community_linking_25&utm_term=community&utm_content=cta-community)