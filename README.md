# niimbot-javacreate a git repo and push to https://github.com/thiagodemoura/niimbot-java.git

Java driver for **Niimbot** label printers over Bluetooth Low Energy — a port of
[iscarelli/niimbot-web-bluetooth](https://github.com/iscarelli/niimbot-web-bluetooth) (MIT)
from browser JavaScript to plain Java 25 (LTS).

Same protocol, same behaviour: V4 framing, both print tasks (`v4` and `b1`), the B1
post-connect handshake, per-model pacing and frame bundling, run-length row encoding,
copies uploaded once, continuous multi-label batches, and **"confirmed or throw"**
semantics (a job only returns when the printer's page counter reached the total;
otherwise PrintEnd is still sent so the paper feeds out, then it throws).

The write stream is verified **byte-for-byte** against the original JS driver
(1,005 BLE writes across 9 scenarios — see `GoldenTest`).

## Supported printers

| Model | id | task | dpi | Default size | Upstream status |
|---|---|---|---|---|---|
| B1 | 4096 | b1 | 203 | `T50x30_b1` (384×240) | printed |
| B1 Pro | 4097 | v4 | 300 | `T50x30` (576×354) | printed |
| B2 Pro | 6912 | v4 | 300 | `T50x30_b2pro` | printed |
| M2-H | 4608 | b1 | 300 | `T50x30_m2h` (567×354) | printed |
| D11_H | 528 | v4 | 300 | `T15x30` (144×354) | printed |
| D110 | 2304 | b1 | 203 | `T15x50` (96×400); also `T12x40` (96×320) | printed (1 page/job) |
| N1 | 3586 | b1 | 203 | `T14x50` (96×400) | printed (1 page/job) |
| B1 SE | 4098 | b1 | 203 | `T50x30_b1` | never printed |
| D110_M | 2320 | v4 | 203 | `T15x50_d110m` | never printed |

The model is **detected automatically** on connect (B1 and B1 Pro advertise the same
name, so detection matters). Run `sizes` for every preset label size.

## Build

```bash
mvn package
# → target/niimbot-java-1.0.1-all.jar  (includes the BLE native libraries)
```

BLE goes through [SimpleJavaBLE](https://docs.simpleble.org/simplejavable/usage)
(`org.simpleble:simplejavable`), which bundles native libraries for Windows 10+,
macOS and Linux (BlueZ). Close the NIIMBOT phone app first — the printer accepts
one BLE connection at a time.

## Command line

```bash
java -jar target/niimbot-java-1.0.1-all.jar scan
java -jar target/niimbot-java-1.0.1-all.jar info
java -jar target/niimbot-java-1.0.1-all.jar print label.png
java -jar target/niimbot-java-1.0.1-all.jar print label.png --copies 3 --density 4
java -jar target/niimbot-java-1.0.1-all.jar print a.png b.png c.png        # one continuous job
java -jar target/niimbot-java-1.0.1-all.jar print --text "Thiago\nNiimbot" --bold
java -jar target/niimbot-java-1.0.1-all.jar print --text "Hi" --model b1 --preview out.png   # no printer
java -jar target/niimbot-java-1.0.1-all.jar print --test --size T12x40                     # calibration label
```

Options: `--printer NAME|ADDRESS` (name prefix, e.g. `B1-H1234`), `--model`,
`--size`, `--copies`, `--density 1-5`, `--rotate` (90° for labels fed standing),
`--font`, `--bold`, `--preview out.png`, `--scan-timeout SECONDS`, `--debug` (packet log).

Exit codes: 0 ok, 1 error, 2 usage, 3 print not confirmed (check the paper).

## Library

Published to GitHub Packages. Add the repository and dependency to your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/thiagodemoura/niimbot-java</url>
    </repository>
</repositories>

<dependency>
    <groupId>dev.niimbot</groupId>
    <artifactId>niimbot-java</artifactId>
    <version>1.0.1</version>
</dependency>
```

GitHub Packages requires authentication even for public packages: add a
`<server><id>github</id>` with your GitHub username and a token with `read:packages`
to `~/.m2/settings.xml`. Alternatively, download the jars from
[Releases](https://github.com/thiagodemoura/niimbot-java/releases).
For integrating into an app, see [examples/](examples/).

```java
import dev.niimbot.*;
import dev.niimbot.ble.SimpleBleTransport;

try (NiimbotPrinter p = new NiimbotPrinter(SimpleBleTransport.connect("B1", 10_000))) {
    p.connect();                                          // identifies the model
    LabelSize size = LabelSize.defaultFor(p.model());     // or LabelSize.preset("T50x30_b1")

    // one image, 3 identical copies (uploaded once)
    p.print(ImageIO.read(new File("label.png")), size,
            NiimbotPrinter.PrintOptions.defaults().copies(3).density(3)
                    .onProgress(System.out::println));

    // different labels back to back, no paper retract between them
    p.printBatchImages(List.of(img1, img2, img3), size, NiimbotPrinter.PrintOptions.defaults());
} catch (NiimbotException.PrintNotConfirmed e) {
    // the printer did not confirm every page — some labels may be blank/short
}
```

Images are scaled to the label size, composited on white and thresholded at
luminance 128 (no dithering) — design them at the label's pixel size in pure black
and white for crisp output. `LabelBitmap.renderText(...)` makes quick text labels.

Other transports: implement `dev.niimbot.Transport` (write bytes, deliver
notifications). The core package has no dependencies.

Tunables on `NiimbotPrinter`: `setWriteMode` (FAST/PACED/ACKED override),
`setBundle`, `setPaceMs` (10), `setPageAckMs` (10 s), `setPageWaitMs` (25 s),
`setLookahead` (2), `setDebug`, `setLogger`. Raw status: `heartbeat()`,
`rfidInfo()`, `printStatus()`, `probe()`.

## Layout

```
src/main/java/dev/niimbot/
  Packet.java          frame encode / stream parser
  PrinterModel.java    model registry (task, dpi, pacing, bundling, pages/job)
  LabelSize.java       label size presets
  LabelBitmap.java     image → 1-bit rows, run-length row packets, text rendering
  NiimbotPrinter.java  connect / identify / handshake / print jobs
  Transport.java       byte-pipe interface
  ble/SimpleBleTransport.java   BLE via SimpleJavaBLE
  cli/NiimbotCli.java  command line
src/test/…             golden test vs the JS driver + fault-injection tests
```

## Notes carried over from upstream

- Width must be the **printhead** width, not the label width — columns past it are
  silently dropped (B1: 384 px = 48 mm of a 50 mm label).
- The B1 drops rows on an unpaced burst → 10 ms pacing; the driver picks this per model.
  On macOS every model is paced (CoreBluetooth drops unacknowledged bursts).
- D110 / N1 / D110_M print only the first page of a multi-page job, so copies and
  batches are split into separate jobs automatically.
- Consumable status (lid, paper, RFID) is exposed raw only; its byte layout is
  confirmed on the B1 Pro alone.

License: MIT (port of iscarelli/niimbot-web-bluetooth, © 2026 iscarelli).
