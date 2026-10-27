param(
    [string]$RepositoryRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$svg = Join-Path $RepositoryRoot "app/src/main/assets/hugo/hugo-face.svg"
$output = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_face.png"
$bootOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_face_boot.png"
$baseOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_face_base.png"
$leftEyeOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_left_eye.png"
$rightEyeOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_right_eye.png"
$leftSocketOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_left_eye_socket.png"
$leftGlobeOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_left_eye_globe.png"
$rightSocketOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_right_eye_socket.png"
$rightGlobeOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_right_eye_globe.png"
$leftReadyOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_left_eye_ready.png"
$rightReadyOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_right_eye_ready.png"
$mouthOutput = Join-Path $RepositoryRoot "app/src/main/res/drawable-nodpi/hugo_mouth.png"
$chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
if (-not (Test-Path -LiteralPath $chrome -PathType Leaf)) { throw "Chrome renderer unavailable" }
if (-not (Test-Path -LiteralPath $svg -PathType Leaf)) { throw "HUGO SVG master unavailable" }

$temporary = Join-Path ([System.IO.Path]::GetTempPath()) ("hugo-face-opaque-{0}.png" -f [guid]::NewGuid().ToString("N"))
$uri = [System.Uri]::new($svg).AbsoluteUri
& $chrome --headless=new --disable-gpu --hide-scrollbars --window-size=1024,1024 --screenshot=$temporary $uri
for ($attempt = 0; $attempt -lt 50 -and -not (Test-Path -LiteralPath $temporary -PathType Leaf); $attempt++) {
    Start-Sleep -Milliseconds 100
}
if (-not (Test-Path -LiteralPath $temporary -PathType Leaf)) {
    throw "Chrome SVG render failed"
}

Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

public static class HugoFaceTransparency {
    public static void Convert(
        string input,
        string output,
        string bootOutput,
        string baseOutput,
        string leftEyeOutput,
        string rightEyeOutput,
        string leftSocketOutput,
        string leftGlobeOutput,
        string rightSocketOutput,
        string rightGlobeOutput,
        string leftReadyOutput,
        string rightReadyOutput,
        string mouthOutput
    ) {
        using (var source = new Bitmap(input))
        using (var image = new Bitmap(source.Width, source.Height, PixelFormat.Format32bppArgb)) {
            using (var graphics = Graphics.FromImage(image)) graphics.DrawImageUnscaled(source, 0, 0);
            var rect = new Rectangle(0, 0, image.Width, image.Height);
            var data = image.LockBits(rect, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
            try {
                var bytes = new byte[Math.Abs(data.Stride) * image.Height];
                Marshal.Copy(data.Scan0, bytes, 0, bytes.Length);
                const int backgroundR = 1;
                const int backgroundG = 6;
                const int backgroundB = 34;
                for (var y = 0; y < image.Height; y++) {
                    for (var x = 0; x < image.Width; x++) {
                        var offset = y * data.Stride + x * 4;
                        var db = bytes[offset] - backgroundB;
                        var dg = bytes[offset + 1] - backgroundG;
                        var dr = bytes[offset + 2] - backgroundR;
                        var distance = Math.Sqrt(dr * dr + dg * dg + db * db);
                        if (distance <= 5) bytes[offset + 3] = 0;
                        else if (distance < 30) bytes[offset + 3] = (byte)(bytes[offset + 3] * (distance - 5) / 25);

                        // Remove the traced lower/side face perimeter so the live Matrix field is
                        // the only field. The hair, eyes, brows and mouth remain independent marks.
                        var nx = (x - 512.0) / 430.0;
                        var ny = (y - 555.0) / 435.0;
                        var ellipse = nx * nx + ny * ny;
                        if (y > 425 && ellipse > 0.84 && ellipse < 1.16) bytes[offset + 3] = 0;
                    }
                }
                Marshal.Copy(bytes, 0, data.Scan0, bytes.Length);
            } finally {
                image.UnlockBits(data);
            }
            image.Save(output, ImageFormat.Png);
            SaveVariant(image, bootOutput, 3);
            SaveVariant(image, baseOutput, 0);
            SaveVariant(image, leftEyeOutput, 1);
            SaveVariant(image, rightEyeOutput, 2);
            SaveVariant(image, leftSocketOutput, 4);
            SaveVariant(image, leftGlobeOutput, 5);
            SaveVariant(image, rightSocketOutput, 6);
            SaveVariant(image, rightGlobeOutput, 7);
            SaveVariant(image, mouthOutput, 8);
            SaveVariant(image, leftReadyOutput, 9);
            SaveVariant(image, rightReadyOutput, 10);
        }
    }

    // Every animation layer is a disjoint pixel selection from the same master.
    // Ready variants recolor only high-luminance sclera pixels.
    private static void SaveVariant(Bitmap source, string output, int mode) {
        using (var variant = new Bitmap(source)) {
            var rect = new Rectangle(0, 0, variant.Width, variant.Height);
            var data = variant.LockBits(rect, ImageLockMode.ReadWrite, PixelFormat.Format32bppArgb);
            try {
                var bytes = new byte[Math.Abs(data.Stride) * variant.Height];
                Marshal.Copy(data.Scan0, bytes, 0, bytes.Length);
                for (var y = 0; y < variant.Height; y++) {
                    for (var x = 0; x < variant.Width; x++) {
                        var radiusX = mode == 3 ? 175.0 : 155.0;
                        var radiusY = mode == 3 ? 140.0 : 122.0;
                        var leftX = (x - 297.0) / radiusX;
                        var rightX = (x - 737.0) / radiusX;
                        var eyeY = (y - 640.0) / radiusY;
                        var inLeft = leftX * leftX + eyeY * eyeY <= 1.0;
                        var inRight = rightX * rightX + eyeY * eyeY <= 1.0;
                        var leftGlobeX = (x - 297.0) / 112.0;
                        var rightGlobeX = (x - 737.0) / 112.0;
                        var globeY = (y - 640.0) / 86.0;
                        var inLeftGlobe = inLeft && leftGlobeX * leftGlobeX + globeY * globeY <= 1.0;
                        var inRightGlobe = inRight && rightGlobeX * rightGlobeX + globeY * globeY <= 1.0;
                        var leftSocketHoleX = (x - 297.0) / 98.0;
                        var rightSocketHoleX = (x - 737.0) / 98.0;
                        var socketHoleY = (y - 640.0) / 74.0;
                        var inLeftSocketHole = inLeft && leftSocketHoleX * leftSocketHoleX + socketHoleY * socketHoleY <= 1.0;
                        var inRightSocketHole = inRight && rightSocketHoleX * rightSocketHoleX + socketHoleY * socketHoleY <= 1.0;
                        var mouthX = (x - 512.0) / 100.0;
                        var mouthY = (y - 900.0) / 42.0;
                        var inMouth = mouthX * mouthX + mouthY * mouthY <= 1.0;
                        var keep = mode == 0 ? !(inLeft || inRight || inMouth) :
                            mode == 1 || mode == 9 ? inLeft :
                            mode == 2 || mode == 10 ? inRight :
                            mode == 4 ? inLeft && !inLeftSocketHole :
                            mode == 5 ? inLeftGlobe :
                            mode == 6 ? inRight && !inRightSocketHole :
                            mode == 7 ? inRightGlobe :
                            mode == 8 ? inMouth :
                            !(inLeft || inRight);
                        var offset = y * data.Stride + x * 4;
                        if (!keep) {
                            bytes[offset] = 0;
                            bytes[offset + 1] = 0;
                            bytes[offset + 2] = 0;
                            bytes[offset + 3] = 0;
                        } else if (mode == 9 || mode == 10) {
                            var blue = bytes[offset];
                            var green = bytes[offset + 1];
                            var red = bytes[offset + 2];
                            if (red > 115 && green > 145 && blue > 160 && red + green + blue > 470) {
                                var brightness = (red + green + blue) / 3;
                                bytes[offset] = (byte)Math.Min(205, brightness * 0.68);
                                bytes[offset + 1] = (byte)Math.Min(255, brightness * 1.08);
                                bytes[offset + 2] = (byte)Math.Min(125, brightness * 0.38);
                            }
                        }
                    }
                }
                Marshal.Copy(bytes, 0, data.Scan0, bytes.Length);
            } finally {
                variant.UnlockBits(data);
            }
            variant.Save(output, ImageFormat.Png);
        }
    }
}
'@

[HugoFaceTransparency]::Convert(
    $temporary,
    $output,
    $bootOutput,
    $baseOutput,
    $leftEyeOutput,
    $rightEyeOutput,
    $leftSocketOutput,
    $leftGlobeOutput,
    $rightSocketOutput,
    $rightGlobeOutput,
    $leftReadyOutput,
    $rightReadyOutput,
    $mouthOutput
)
Remove-Item -LiteralPath $temporary -Force
$result = Get-Item -LiteralPath $output
$digest = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
$bootResult = Get-Item -LiteralPath $bootOutput
$bootDigest = (Get-FileHash -LiteralPath $bootOutput -Algorithm SHA256).Hash.ToLowerInvariant()
$baseResult = Get-Item -LiteralPath $baseOutput
$baseDigest = (Get-FileHash -LiteralPath $baseOutput -Algorithm SHA256).Hash.ToLowerInvariant()
$leftEyeResult = Get-Item -LiteralPath $leftEyeOutput
$leftEyeDigest = (Get-FileHash -LiteralPath $leftEyeOutput -Algorithm SHA256).Hash.ToLowerInvariant()
$rightEyeResult = Get-Item -LiteralPath $rightEyeOutput
$rightEyeDigest = (Get-FileHash -LiteralPath $rightEyeOutput -Algorithm SHA256).Hash.ToLowerInvariant()
$layerOutputs = @(
    $leftSocketOutput,
    $leftGlobeOutput,
    $rightSocketOutput,
    $rightGlobeOutput,
    $leftReadyOutput,
    $rightReadyOutput,
    $mouthOutput
)
Write-Output "HUGO transparent face raster: $($result.Length) bytes sha256=$digest"
Write-Output "HUGO transparent boot raster: $($bootResult.Length) bytes sha256=$bootDigest"
Write-Output "HUGO no-eyes base raster: $($baseResult.Length) bytes sha256=$baseDigest"
Write-Output "HUGO left-eye raster: $($leftEyeResult.Length) bytes sha256=$leftEyeDigest"
Write-Output "HUGO right-eye raster: $($rightEyeResult.Length) bytes sha256=$rightEyeDigest"
foreach ($layerOutput in $layerOutputs) {
    $layerResult = Get-Item -LiteralPath $layerOutput
    $layerDigest = (Get-FileHash -LiteralPath $layerOutput -Algorithm SHA256).Hash.ToLowerInvariant()
    Write-Output "HUGO animation layer $($layerResult.Name): $($layerResult.Length) bytes sha256=$layerDigest"
}
