# ADR 0001: Keep the Agent Platform as a Modular Monolith

Status: Accepted

The project remains one Spring Boot deployment. Domain seams separate Agent, workflow, tool, knowledge, memory and evaluation modules, while MySQL transactions and the outbox preserve consistency.

This avoids premature microservice coordination and reuses the existing MySQL, Redis, Redis Stack, MyBatis-Plus and LangChain4j stack. ArchUnit guards package cycles so a future extraction remains possible.
