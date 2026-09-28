Bitácora — Asignación 1

Programación III — StockFlow

Tarea delegada al agente

Durante la preparación inicial del proyecto StockFlow le pedí al agente que me guiara para configurar y comprobar el entorno necesario para ejecutar un proyecto Java con Spring Boot y Maven Wrapper.

El objetivo era poder ejecutar correctamente:

.\mvnw.cmd test

antes de continuar con los commits y pull requests de la asignación.

Qué le pedí

Le pedí ayuda para identificar por qué el proyecto no podía ejecutar las pruebas de Maven.

El primer error obtenido fue:

The JAVA_HOME environment variable is not defined correctly,
this environment variable is needed to run this program.

El agente me pidió comprobar la instalación de Java utilizando:

java -version
where.exe java
$env:JAVA_HOME

Los comandos mostraron que Java no estaba instalado en el equipo.

Qué me devolvió el agente

El agente recomendó utilizar Java 21, que es la versión configurada para StockFlow.

Inicialmente me indicó instalar Eclipse Temurin 21 utilizando:

winget install EclipseAdoptium.Temurin.21.JDK

También explicó que después de la instalación debía comprobar:

java -version
javac -version
$env:JAVA_HOME

y luego volver a ejecutar:

.\mvnw.cmd test

Error detectado en la respuesta del agente

El agente asumió que winget estaba disponible y funcionando en mi equipo antes de recomendar ese método de instalación.

Al ejecutar el comando:

winget install EclipseAdoptium.Temurin.21.JDK

Windows devolvió el error:

Error al ejecutar el programa 'winget.exe':
El sistema no puede encontrar la ruta especificada.

Esto demostró que el procedimiento propuesto inicialmente no funcionaba en mi entorno.

Cómo detecté y corregí el error

Detecté el problema al ejecutar exactamente el comando indicado y revisar el mensaje de error generado por PowerShell.

Le envié nuevamente el resultado al agente. Después de revisar el error, el agente corrigió la recomendación y propuso instalar manualmente Eclipse Temurin JDK 21 utilizando el instalador MSI oficial.

Después de realizar la instalación manual, comprobé Java con:

java -version

obteniendo:

openjdk version "21.0.12.1" 2026-08-18 LTS
OpenJDK Runtime Environment Temurin-21.0.12.1+1
OpenJDK 64-Bit Server VM Temurin-21.0.12.1+1

Finalmente ejecuté:

.\mvnw.cmd test

y Maven terminó correctamente con:

Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS

Resultado

El uso del agente permitió identificar el problema del entorno, instalar correctamente Java 21 y comprobar que StockFlow compilaba y ejecutaba sus pruebas.

También fue necesario validar manualmente las instrucciones recibidas, ya que la primera alternativa propuesta por el agente no era compatible con la configuración del equipo.

Este proceso mostró la importancia de no ejecutar instrucciones del agente de forma automática, sino comprobar su resultado y corregirlas cuando sea necesario.