# Integration example

How to use the driver from an application (not part of the library jar).

| File | What it shows |
|---|---|
| `LabelPrintService.java` | One reusable BLE connection, jobs queued on one worker thread, reconnect after a dropped link, `CompletableFuture` API |
| `ProductLabel.java` | Drawing your own label layout with Java2D at the label's pixel size |
| `ExampleApp.java` | Single label with copies, a mixed batch, non-blocking use, and a preview with no printer |

## Run

```bash
mvn package                       # builds target/classes from the repo root

# Windows uses ';' in the classpath; macOS/Linux use ':'
CP="target/classes;$HOME/.m2/repository/org/simpleble/simplejavable/1.2.0/simplejavable-1.2.0.jar"
javac -d target/examples -cp "$CP" examples/src/com/example/labels/*.java

java -cp "target/examples;$CP" com.example.labels.ExampleApp preview      # writes preview-product.png
java -cp "target/examples;$CP" com.example.labels.ExampleApp print B1     # prints to printer "B1…"
```

## In your own project

Add the library (for example `mvn install` here, then depend on
`dev.niimbot:niimbot-java:1.0.0`), copy `LabelPrintService`, and:

```java
var service = new LabelPrintService(LabelPrintService.Config.defaults("B1"), System.out::println);
LabelSize size = service.connect().join();
service.print(ProductLabel.render(size, "Coffee", "CB-1", "$9.90"), 1)
       .exceptionally(err -> { showError(err); return null; });
// on shutdown:
service.close();
```

A job that fails with `NiimbotException.PrintNotConfirmed` is not retried automatically,
because some labels may already have printed. Let the user choose whether to print again.
