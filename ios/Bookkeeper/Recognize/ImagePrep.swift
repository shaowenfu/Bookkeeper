import UIKit

/// 与 Android 版 Images.prepareForUpload 一致：宽缩到 ≤1080；高 > 2.5 倍宽的长截图切段，每段高 2 倍宽、重叠 8%。
enum ImagePrep {
    static func prepareForUpload(_ url: URL, maxWidth: CGFloat = 1080) throws -> [String] {
        guard let image = UIImage(contentsOfFile: url.path), let cg = image.cgImage else {
            throw DeepSeekError(message: "图片无法解码")
        }
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        let scale = min(1, maxWidth / w)
        let tw = (w * scale).rounded(), th = (h * scale).rounded()
        let scaled = render(cg, size: CGSize(width: tw, height: th))

        guard th > tw * 2.5 else { return [try b64(scaled)] }
        var parts: [String] = []
        let partH = tw * 2
        let step = (partH * 0.92).rounded()
        var top: CGFloat = 0
        while top < th {
            let ph = min(partH, th - top)
            guard let piece = scaled.cgImage?.cropping(to: CGRect(x: 0, y: top, width: tw, height: ph)) else { break }
            parts.append(try b64(UIImage(cgImage: piece)))
            if top + ph >= th { break }
            top += step
        }
        return parts
    }

    /// 把任意图片数据转成 JPEG 保存用
    static func jpegData(from data: Data) -> Data? {
        guard let img = UIImage(data: data) else { return nil }
        return img.jpegData(compressionQuality: 0.88)
    }

    private static func render(_ cg: CGImage, size: CGSize) -> UIImage {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        format.opaque = true
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            UIImage(cgImage: cg).draw(in: CGRect(origin: .zero, size: size))
        }
    }

    private static func b64(_ img: UIImage) throws -> String {
        guard let d = img.jpegData(compressionQuality: 0.85) else { throw DeepSeekError(message: "图片编码失败") }
        return d.base64EncodedString()
    }
}
