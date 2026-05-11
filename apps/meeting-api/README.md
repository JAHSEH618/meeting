# meeting-api

Java 17 + Spring Boot + COLA-V5 模块化单体。

Maven 模块：

```text
meeting-api-start
meeting-api-client
meeting-api-adapter
meeting-api-app
meeting-api-domain
meeting-api-infrastructure
```

业务域作为各模块内 package 边界：`api/bff`、`user-auth`、`meeting`、`task`、`storage`、`llm-gateway`、`speaker`、`rag`、`document`、`export`、`audit`。

