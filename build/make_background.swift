import AppKit
import CoreGraphics

let size = CGSize(width: 660, height: 400)
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(size.width), pixelsHigh: Int(size.height), bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
let ctx = NSGraphicsContext.current!.cgContext

let rect = CGRect(origin: .zero, size: size)

// Base: deep navy gradient
let colors = [
    CGColor(red: 0.055, green: 0.075, blue: 0.13, alpha: 1),
    CGColor(red: 0.09, green: 0.11, blue: 0.20, alpha: 1)
] as CFArray
let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1])!
ctx.drawLinearGradient(grad, start: CGPoint(x: 0, y: size.height), end: CGPoint(x: size.width, y: 0), options: [])

// Subtle ambient glow (gold) top-right
let glowColors = [
    CGColor(red: 0.95, green: 0.75, blue: 0.35, alpha: 0.10),
    CGColor(red: 0.95, green: 0.75, blue: 0.35, alpha: 0)
] as CFArray
let glowGrad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: glowColors, locations: [0, 1])!
ctx.drawRadialGradient(glowGrad, startCenter: CGPoint(x: size.width * 0.85, y: size.height * 0.9), startRadius: 0, endCenter: CGPoint(x: size.width * 0.85, y: size.height * 0.9), endRadius: 380, options: [])

// Mint glow bottom-left, faint
let mintColors = [
    CGColor(red: 0.3, green: 0.85, blue: 0.7, alpha: 0.06),
    CGColor(red: 0.3, green: 0.85, blue: 0.7, alpha: 0)
] as CFArray
let mintGrad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: mintColors, locations: [0, 1])!
ctx.drawRadialGradient(mintGrad, startCenter: CGPoint(x: size.width * 0.1, y: size.height * 0.05), startRadius: 0, endCenter: CGPoint(x: size.width * 0.1, y: size.height * 0.05), endRadius: 320, options: [])

// Arrow from app (left) to Applications (right)
ctx.setLineDash(phase: 0, lengths: [1, 12])
ctx.setLineCap(.round)
ctx.setLineWidth(4)
ctx.setStrokeColor(CGColor(red: 0.95, green: 0.78, blue: 0.38, alpha: 0.5))
ctx.move(to: CGPoint(x: 235, y: 200))
ctx.addLine(to: CGPoint(x: 425, y: 200))
ctx.strokePath()
ctx.setLineDash(phase: 0, lengths: [])

// Arrowhead
ctx.setFillColor(CGColor(red: 0.95, green: 0.78, blue: 0.38, alpha: 0.5))
let ax: CGFloat = 448, ay: CGFloat = 200
let arrow = CGMutablePath()
arrow.move(to: CGPoint(x: ax, y: ay))
arrow.addLine(to: CGPoint(x: ax - 22, y: ay + 9))
arrow.addLine(to: CGPoint(x: ax - 22, y: ay - 9))
arrow.closeSubpath()
ctx.addPath(arrow)
ctx.fillPath()

// Caption text
let para = NSMutableParagraphStyle()
para.alignment = .center
let attrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.systemFont(ofSize: 15, weight: .medium),
    .foregroundColor: NSColor(calibratedRed: 0.95, green: 0.78, blue: 0.38, alpha: 0.85),
    .paragraphStyle: para
]
let s = NSString(string: "拖动应用到 Applications 文件夹安装")
s.draw(at: NSPoint(x: 0, y: 320), withAttributes: attrs)

NSGraphicsContext.restoreGraphicsState()

let data = rep.representation(using: .png, properties: [:])!
let out = URL(fileURLWithPath: CommandLine.arguments[1])
try! data.write(to: out)
print("background written to \(out.path)")
