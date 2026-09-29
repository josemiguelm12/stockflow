# StockFlow

Aplicación web de gestión de inventario desarrollada con Spring Boot 4.1 y Java 21. Por ahora expone un endpoint de salud

## Requisitos

- **JDK 21 o superior.** Comprueba tu versión con `java -version`.
- **Git.**
- **No hace falta instalar Maven.** El proyecto trae Maven Wrapper (`mvnw` y `mvnw.cmd`),
  que descarga Maven 3.9.16 y las dependencias la primera vez que se ejecuta. Para esa
  primera ejecución se necesita internet.

## 1. Clonar el repositorio

```bash
git clone https://github.com/josemiguelm12/stockflow.git
cd stockflow
```

## 2. Instalar las dependencias y ejecutar las pruebas

En Windows (PowerShell):

```powershell
.\mvnw.cmd test
```

En Linux, macOS o Git Bash:

```bash
./mvnw test
```

Debe terminar con `BUILD SUCCESS`.

## 3. Levantar la aplicación

En Windows (PowerShell):

```powershell
.\mvnw.cmd spring-boot:run
```

En Linux, macOS o Git Bash:

```bash
./mvnw spring-boot:run
```

La aplicación queda escuchando en `http://localhost:8080`. Para detenerla, pulsa `Ctrl + C`
en esa terminal.

## 4. Comprobar que responde

Abre `http://localhost:8080/api/health` en el navegador o, desde otra terminal:

```bash
curl http://localhost:8080/api/health
```

En Windows PowerShell escribe `curl.exe` en lugar de `curl`. La respuesta debe ser
parecida a esta (el orden de los campos puede variar):

```json
{"status":"UP","application":"StockFlow"}
```
