# Rundown JSON Serialization

The `Rundown.toJson()` function provides JSON serialization of execution results with configurable verbosity levels, designed for AI agents and MCP (Model Context Protocol) servers.

## Usage

```kotlin
import com.salesforce.revoman.output.RundownVerbosity

val rundown = ReVoman.revUp(...)

// Default (STANDARD) verbosity
val jsonStandard = rundown.toJson()

// Explicit verbosity levels
val jsonSummary = rundown.toJson(RundownVerbosity.SUMMARY)
val jsonDetailed = rundown.toJson(RundownVerbosity.DETAILED)
val jsonFull = rundown.toJson(RundownVerbosity.FULL)
```

## Verbosity Levels

### SUMMARY
Minimal information for quick overview:
- Execution counts (total, failed, http errors)
- Success flags
- Environment variable keys only (not values)

**Use when:** Quick status check, bandwidth-constrained scenarios, AI agents need high-level overview.

```json
{
  "providedStepsToExecuteCount": 10,
  "executedStepCount": 10,
  "httpFailureStepCount": 0,
  "unsuccessfulStepCount": 0,
  "areAllStepsSuccessful": true,
  "environmentKeys": ["baseUrl", "authToken", "userId"]
}
```

### STANDARD (Default)
Standard information for understanding execution flow:
- All SUMMARY information
- Complete environment with values
- Step summaries (name, index, success status)
- Failure information (if any)
- First unsuccessful step details

**Use when:** Understanding what happened without excessive detail, default for most AI agent interactions.

```json
{
  "providedStepsToExecuteCount": 10,
  "executedStepCount": 10,
  "environment": {
    "baseUrl": "https://api.example.com",
    "authToken": "xyz123",
    "userId": "user-456"
  },
  "stepReports": [
    {
      "step": {
        "index": "1",
        "name": "Create User",
        "displayName": "1 ### POST ~~> Create User",
        "isSuccessful": true
      }
    }
  ]
}
```

### DETAILED
Detailed HTTP-level information without payloads:
- All STANDARD information
- Request/response metadata (URI, method, status codes, headers)
- Hook execution counts
- Stack traces for failures

**Use when:** Debugging or analysis needs HTTP-level details without inspecting full payloads.

```json
{
  "stepReports": [
    {
      "step": {...},
      "requestInfo": {
        "isJson": true,
        "httpMsg": {
          "method": "POST",
          "uri": "https://api.example.com/users",
          "headers": {
            "Content-Type": "application/json",
            "Authorization": "Bearer xyz123"
          }
        }
      },
      "responseInfo": {
        "httpMsg": {
          "statusCode": 201,
          "statusDescription": "Created",
          "successful": true,
          "headers": {
            "Content-Type": "application/json"
          }
        }
      }
    }
  ]
}
```

### FULL
Complete information for forensic analysis:
- All DETAILED information
- Request/response bodies
- Complete environment snapshot per step
- All polling response details

**Use when:** Complete forensic analysis, debugging complex issues, need full request/response inspection.

```json
{
  "stepReports": [
    {
      "requestInfo": {
        "httpMsg": {
          "method": "POST",
          "uri": "https://api.example.com/users",
          "headers": {...},
          "body": "{\"name\":\"John Doe\",\"email\":\"john@example.com\"}"
        }
      },
      "responseInfo": {
        "httpMsg": {
          "statusCode": 201,
          "body": "{\"id\":\"123\",\"name\":\"John Doe\",\"email\":\"john@example.com\"}"
        }
      },
      "pmEnvSnapshot": {
        "baseUrl": "https://api.example.com",
        "userId": "123"
      }
    }
  ]
}
```

## Use Cases

### AI Agents / MCP Servers
- **SUMMARY**: Quick health checks, monitoring dashboards
- **STANDARD**: Default context for AI agents to understand execution results
- **DETAILED**: When AI needs to debug HTTP-level issues
- **FULL**: When AI needs complete context for complex debugging

### Performance Considerations
- **SUMMARY**: ~5-10% of FULL size
- **STANDARD**: ~30-40% of FULL size
- **DETAILED**: ~60-70% of FULL size
- **FULL**: Complete data, can be large for many steps

## Integration Examples

### MCP Server
```kotlin
@Tool
fun getExecutionResults(verbosity: String = "STANDARD"): String {
  val rundown = executeTestSuite()
  val verbosityLevel = RundownVerbosity.valueOf(verbosity.uppercase())
  return rundown.toJson(verbosityLevel)
}
```

### Log-based AI Analysis
```kotlin
// Quick check in logs
logger.info("Execution summary: ${rundown.toJson(RundownVerbosity.SUMMARY)}")

// Detailed failure analysis
if (!rundown.areAllStepsSuccessful) {
  logger.error("Execution failed: ${rundown.toJson(RundownVerbosity.DETAILED)}")
}
```

## Implementation Details

- Uses internal `JsonWriterUtils` for consistent JSON formatting
- Output is pretty-printed with 2-space indentation
- All JSON values are serialized using Moshi adapters
- Null values are explicitly included as `null` in JSON
- The output is valid, parseable JSON at all verbosity levels
