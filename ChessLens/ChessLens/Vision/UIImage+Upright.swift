import UIKit

extension UIImage {
    /// A CGImage whose pixel grid matches what's shown on screen. Frames may arrive backed by a
    /// CIImage or with a non-up orientation; both are redrawn so pixel coordinates are consistent.
    func uprightCGImage() -> CGImage? {
        if imageOrientation == .up, let cgImage { return cgImage }
        let format = UIGraphicsImageRendererFormat()
        format.scale = scale
        return UIGraphicsImageRenderer(size: size, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: size))
        }.cgImage
    }
}
