import Foundation
import UIKit

struct RecognizedBoard {
    let board: ChessBoard
    let notes: String
}

enum RecognitionError: LocalizedError {
    case missingAPIKey
    case imageEncodingFailed
    case noBoardVisible(String)
    case unreadableBoard([String])
    case refused(String)
    case truncated
    case api(status: Int, message: String)
    case malformedResponse

    var errorDescription: String? {
        switch self {
        case .missingAPIKey: return "Add your Anthropic API key in Settings."
        case .imageEncodingFailed: return "Couldn't encode the photo."
        case .noBoardVisible(let notes): return "No chess board found in the photo. \(notes)"
        case .unreadableBoard(let ranks): return "The board read back in an unexpected format: \(ranks.joined(separator: "/"))"
        case .refused(let why): return "The request was declined. \(why)"
        case .truncated: return "The board reading was cut off. Try again."
        case .api(let status, let message): return "Claude API error \(status): \(message)"
        case .malformedResponse: return "Unexpected response from the Claude API."
        }
    }
}

/// Reads the pieces off a photo of a physical board with Claude's vision, returning a board
/// in standard orientation (rank 8 at the top).
struct BoardRecognizer {
    static let endpoint = URL(string: "https://api.anthropic.com/v1/messages")!

    var apiKey: String
    var model: String
    var effort: String

    func recognize(_ image: UIImage, playerColor: PieceColor) async throws -> RecognizedBoard {
        guard !apiKey.isEmpty else { throw RecognitionError.missingAPIKey }
        guard let jpeg = image.resizedForVision().jpegData(compressionQuality: 0.85) else {
            throw RecognitionError.imageEncodingFailed
        }

        var request = URLRequest(url: Self.endpoint, timeoutInterval: 180)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "content-type")
        request.setValue(apiKey, forHTTPHeaderField: "x-api-key")
        request.setValue("2026-10-21", forHTTPHeaderField: "anthropic-version")

        var body: [String: Any] = [
            "model": model,
            "max_tokens": 16000,
            "system": Self.systemPrompt,
            "output_config": [
                "effort": effort,
                "format": ["type": "json_schema", "schema": Self.schema],
            ],
            "messages": [[
                "role": "user",
                "content": [
                    [
                        "type": "image",
                        "source": [
                            "type": "base64",
                            "media_type": "image/jpeg",
                            "data": jpeg.base64EncodedString(),
                        ],
                    ],
                    ["type": "text", "text": Self.userPrompt(playerColor: playerColor)],
                ],
            ]],
        ]
        // Haiku has no server-side fallback; on the other models a declined request is retried
        // on Anthropic's recommended fallback model instead of failing.
        if !model.contains("haiku") {
            body["fallbacks"] = "default"
            request.setValue("server-side-fallback-2026-07-01", forHTTPHeaderField: "anthropic-beta")
        }
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, response) = try await URLSession.shared.data(for: request)
        let status = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw RecognitionError.api(status: status, message: String(data: data, encoding: .utf8) ?? "")
        }
        guard status == 200 else {
            let message = (json["error"] as? [String: Any])?["message"] as? String ?? "Unknown error"
            throw RecognitionError.api(status: status, message: message)
        }

        switch json["stop_reason"] as? String {
        case "refusal":
            let details = json["stop_details"] as? [String: Any]
            throw RecognitionError.refused(details?["explanation"] as? String ?? "")
        case "max_tokens":
            throw RecognitionError.truncated
        default:
            break
        }

        let blocks = json["content"] as? [[String: Any]] ?? []
        guard let text = blocks.first(where: { $0["type"] as? String == "text" })?["text"] as? String,
              let payload = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [String: Any]
        else { throw RecognitionError.malformedResponse }

        let notes = payload["notes"] as? String ?? ""
        guard payload["board_visible"] as? Bool ?? false else {
            throw RecognitionError.noBoardVisible(notes)
        }
        let ranks = (payload["ranks"] as? [String] ?? []).map { $0.trimmingCharacters(in: .whitespaces) }
        guard let board = ChessBoard(ranks: ranks) else { throw RecognitionError.unreadableBoard(ranks) }
        return RecognizedBoard(board: board, notes: notes)
    }

    private static let schema: [String: Any] = [
        "type": "object",
        "properties": [
            "board_visible": [
                "type": "boolean",
                "description": "False when no chess board is visible or it is too blurry or cropped to read.",
            ],
            "ranks": [
                "type": "array",
                "items": ["type": "string"],
                "description": "Exactly 8 strings, rank 8 first and rank 1 last. Each has exactly 8 characters for files a to h.",
            ],
            "notes": [
                "type": "string",
                "description": "Short note on any square you were unsure about, or how you oriented the board.",
            ],
        ],
        "required": ["board_visible", "ranks", "notes"],
        "additionalProperties": false,
    ]

    private static let systemPrompt = """
    You transcribe photos of physical chess boards into an exact piece layout. \
    The photos come from smart glasses worn by a player sitting at the board, so the board is usually \
    seen at an angle, may be partly cropped, and may show hands, clocks or captured pieces beside it. \
    Only pieces standing on the 64 squares count; ignore captured pieces off the board.
    """

    private static func userPrompt(playerColor: PieceColor) -> String {
        """
        Transcribe the position on this chess board.

        Orientation: the photo was taken by the player of the \(playerColor.displayName) pieces from their seat, \
        so \(playerColor.displayName)'s back rank (rank \(playerColor == .white ? 1 : 8)) is normally the edge nearest the camera. \
        Confirm with any coordinates printed on the board, and with the rule that each player has a light square \
        in their right-hand corner (h1 and a8 are light). If those clues disagree with the seating assumption, trust the clues.

        Output 8 strings in "ranks": the first is rank 8 and the last is rank 1. Each string has exactly 8 characters, \
        file a to file h, using K Q R B N P for White pieces, k q r b n p for Black pieces, and . for an empty square. \
        Work through the board one rank at a time and check every square, paying close attention to telling \
        bishops from pawns and kings from queens. Each side has exactly one king.
        """
    }
}

extension UIImage {
    /// Scales down so the long edge is at most `maxDimension` pixels, which is what the
    /// vision model reads at full detail; larger images only add upload time.
    func resizedForVision(maxDimension: CGFloat = 1568) -> UIImage {
        let pixelWidth = size.width * scale
        let pixelHeight = size.height * scale
        let longEdge = max(pixelWidth, pixelHeight)
        guard longEdge > maxDimension else { return self }
        let factor = maxDimension / longEdge
        let target = CGSize(width: (pixelWidth * factor).rounded(), height: (pixelHeight * factor).rounded())
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        return UIGraphicsImageRenderer(size: target, format: format).image { _ in
            draw(in: CGRect(origin: .zero, size: target))
        }
    }
}
