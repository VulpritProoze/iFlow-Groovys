# SAP BTP - Integration Flow Groovy Scripts

This repository contains a collection of script classes and utilities specifically designed for SAP Cloud Integration (iFlows). It provides robust solutions for handling OData, SOAP, and REST-based communications, specialized for SAP Business One Service Layer and other BTP-integrated services.

## Usage Instructions

- **Naming Conventions**: Files intended to be used as reusable script collections in SAP Cloud Integration should be prefixed with `SC_` (e.g., `SC_ConnectionUtils.groovy`).
- **Documentation**: Use Javadoc-style comments with `@` tags (e.g., `@param`, `@return`) to document class methods and provide example usage.
- **Secure Store**: Always use the provided credential extraction logic to retrieve sensitive data from the SAP BTP Security Material.
- **Error Handling**: Ensure scripts throw a `RuntimeException` when critical configurations or credentials are missing to halt iFlow execution correctly.
