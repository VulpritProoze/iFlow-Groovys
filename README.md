# SAP BTP - Integration Flow Groovy Scripts

This repository contains a collection of script classes and utilities specifically designed for SAP Cloud Integration (iFlows). It provides robust solutions for handling OData, SOAP, and REST-based communications, specialized for SAP Business One Service Layer and other BTP-integrated services.

## Project Structure

- **CollectionsSC**: Stores scripts intended for SAP Script Collections. This includes general-purpose utility scripts but excludes specific logic for getters, setters, API calls, or data mapping.
- **Misc**: Houses reusable logic and helper scripts designed to be appended directly into other scripts. Since SAP BTP iFlows do not natively support package imports from other custom-made scripts, appending this code is the primary strategy for maintaining reusability across the repository.
- **SAPtoWEBPOS**: A feature-specific folder containing API call implementations and data mapping logic for integrations sending data from SAP to WEBPOS.
- **WEBPOStoSAP**: A feature-specific folder containing API call implementations and data mapping logic for integrations sending data from WEBPOS to SAP.

## Documentation & Coding Guidelines

### 1. Documenting Dependencies
Every script must include a docstring at the very top indicating which other scripts from this repository it depends on (e.g., `Dependencies: - Misc/LoggerService.groovy`). Use `None` if the script is standalone.

### 2. Standard Script Header
Below the package imports, provide an exhaustive docstring for the **core classes or primary methods** in the script to avoid cluttering every minor utility function. Key components include:
1. **Short Intro**: A concise summary of the primary purpose.
2. **Additional Details (Optional)**: Extended technical context.
3. **Method Documentation**: For core functions, use `@param` and `@return` tags.
4. **Use Case Scenario**: A code example (marked with `{@code ...}`) showing implementation.

*Note: Minor helper methods or internal logic can use simple multiline comments (`/* ... */`) to maintain readability without full Javadoc overhead.*

### 3. File Organization
- **Usage Strategy**: Prefer appending `Misc` scripts to avoid duplication and ensure easier maintenance.
- **Unused Files**: Any scripts that are no longer active or are kept for historical reference should be placed in the `Unused/` sub-folder within their respective section. These files may not be kept up to date with repository-wide refactors.

### 4. General Best Practices
- **Secure Store**: Always use provided credential extraction logic to retrieve sensitive data from SAP BTP Security Material.
- **Error Handling**: Scripts must follow point 5 (next guideline) when returning an error. Do not throw an exception (because they're untrackable in W3P).
- **Naming Conventions**: Files for script collections should be prefixed with `SC_` (e.g., `SC_MessageContext.groovy`).

### 5. Standardized Return Patterns
For service-level methods (like `LoggerService.logProcess`), prefer returning a **Result Map** instead of throwing exceptions. This allows the calling script to handle errors gracefully without halting the entire iFlow.

**Structure of Result Map:**
- **`status`** (Integer):
    - `1`: Success
    - `0`: Validation Error (e.g., invalid input parameters)
    - `-1`: System/Server Error (e.g., connection failure, credentials missing)
- **`message`** (String): A descriptive summary of the result or error details.
- **`payload`** (Optional): The raw response data (e.g., XML/JSON) from the external service on success.
