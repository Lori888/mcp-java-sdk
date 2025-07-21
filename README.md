📖 中文文档 | [📖 official English Documentation](README_EN.md)

# MCP Java SDK JDK8重构版本说明

## 📋 概述

本项目是官方MCP Java SDK的jdk8重构版本。

官方SDK使用jdk17开发构建，为了能够使得更多使用老版本jdk的项目也能集成开发MCP功能，并且尽量避免重复造轮子，因此选择使用jdk8重构官方版本。

### 项目地址

重构分支项目地址：`https://github.com/Lori888/mcp-java-sdk`   分支`0.10.0-jdk8`（基于原项目分支`0.10.0 revision 6307f069`建立）

## ✨ 重构说明

### 重构原则

- 保持原项目代码中类的注释等
- 尽量和原项目代码的代码顺序保持一致，以便于修改对比

### 重构代码改动

主要进行了以下改动：

- `record` 重构为 `static class`、添加 `@Value`注解（添加`lombok`包依赖）
- `sealed interface` 重构为 `interface`  并相应修改其子类
- `List.of()` 重构为 `Collections.emptyList()`
- `List.of(xxx)` 重构为 `Collections.singletonList(xxx)`  和 `Arrays.stream(handlers).collect(Collectors.toList())`
- `List.of(xxx, xxx)` 重构为 `Arrays.asList(xxx, xxx)`
- `Map.of()` 重构为 `Collections.emptyMap()`
- `Map.of(xxx)` 重构为 `Collections.singletonMap(xxx)`
- `Map.of(xxx, xxx)` 重构为 jdk8 map写法
- `stream().map(xxx).toList()`  重构为 `stream().map(xxx).collect(Collectors.toList())`
- `stream().toList()` 重构为 `stream().collect(Collectors.toList())`
- `Optional.isEmpty()`  重构为 `== null`
- `var` 重构为具体类型
- `instanceof Class xxx` 重构为jdk8写法
- `switch` 重构为jdk8写法
- `jakarta.servlet.*` 重构为 `javax.servlet.*`（添加`jakarta.servlet-api`包依赖）
- `java.net.http.*`  改为使用 `OkHttp`（添加`okhttp`包依赖）

### 功能变更

- 在原项目代码的基础上，新增了`StreamableHttpServerTransportProvider`（复制于`https://github.com/ZachGerman/mcp-java-sdk` 项目分支`StreamableHttpServerTransportProvider` 并用jdk8重构~请给原作者1个小星星）
- 修复了使用`HttpServletSseServerTransportProvider`时进行`tools/call`传参中文乱码问题

## 🛠️ 构建项目

### 环境要求

- Java 8
- Maven 3.3+

### 构建方法

1.下载项目源码：

```
git clone -b 0.10.0-jdk8 https://github.com/Lori888/mcp-java-sdk.git
```

2.构建安装到本地maven仓库中：

```
cd mcp-java-sdk
mvn clean install
```

## 🔥 使用方法

在项目中添加依赖：

```xml
<dependency>
	<groupId>io.modelcontextprotocol.sdk</groupId>
	<artifactId>mcp</artifactId>
	<version>0.10.0-jdk8</version>
</dependency>
```

## 🧪 应用示例

开发1个使用StreamableHttpTransport的MCP Server，关键步骤：

- 构建MCP Server属性对象实例
- 根据MCP Server属性创建对应的`McpServerTransportProvider`实例
- 根据同步/异步类型创建MCP Server实例
- 将提供的能力(`tools/resources/prompts`)注册到MCP Server实例中
- 使用内嵌的`Tomcat`提供Web服务

完整代码详见： [mcp-java-sdk-examples](https://github.com/Lori888/mcp-java-sdk-examples.git) 

## 📑 TODO LIST

- [ ] `mcp-spring-webflux`、`mcp-spring-webmvc`子模块重构
- [x] 加入`HttpClientStreamableHttpTransport`  2025-07-21 已完成
- [ ] 适配Specification 2025-03-26 和 2025-06-18
- [ ] jdk8重构官方主干分支

