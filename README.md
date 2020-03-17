# About [![Build Status](https://travis-ci.org/dernasherbrezon/bmeClient.svg?branch=master)](https://travis-ci.org/dernasherbrezon/bmeClient) [![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=ru.r2cloud%3AbmeClient&metric=alert_status)](https://sonarcloud.io/dashboard?id=ru.r2cloud%3AbmeClient)

Java client for sending telemetry data to gnd.bme.hu

# Usage

1. Register at [https://gnd.bme.hu:8080/index](https://gnd.bme.hu:8080/index)

2. Add maven dependency:

```xml
<dependency>
  <groupId>ru.r2cloud</groupId>
  <artifactId>bmeClient</artifactId>
  <version>1.0</version>
</dependency>
```

3. Setup client and make a request:

```java
BmeClient client = new BmeClient("https://gnd.bme.hu", 8080, 10000, 10000L, username, password);
client.uploadBatch(Satellite.SMOGP, Collections.singletonList(new byte[] { payload } ));
```