import CryptoKit
import UIKit
import WebKit

final class QuickGradeViewController: UIViewController, WKNavigationDelegate, WKUIDelegate, WKScriptMessageHandler {
    private static let shellVersion = "1.2.1"
    private static let bundledRuntimeVersion = "1.3.0"
    private static let manifestURL = URL(string: "https://raw.githubusercontent.com/ebaneymar/Quick-GradeRepository/main/update-manifest.json")!

    private var webView: WKWebView!
    private var runtimeVersion: String {
        UserDefaults.standard.string(forKey: "quickgrade.runtime.version") ?? Self.bundledRuntimeVersion
    }

    override var prefersStatusBarHidden: Bool { true }
    override var prefersHomeIndicatorAutoHidden: Bool { true }
    override var supportedInterfaceOrientations: UIInterfaceOrientationMask { .portrait }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        prepareRuntimeIfNeeded()
        createWebView()
        loadRuntime()
    }

    deinit {
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "quickGrade")
    }

    private var runtimeDirectory: URL {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return support.appendingPathComponent("QuickGradeRuntime", isDirectory: true)
    }

    private var runtimeFile: URL {
        runtimeDirectory.appendingPathComponent("index.html")
    }

    private func prepareRuntimeIfNeeded() {
        do {
            try FileManager.default.createDirectory(at: runtimeDirectory, withIntermediateDirectories: true)
            guard !FileManager.default.fileExists(atPath: runtimeFile.path) else { return }
            guard let bundled = Bundle.main.url(forResource: "index", withExtension: "html") else {
                throw RuntimeError("Bundled QuickGrade runtime is missing.")
            }
            try FileManager.default.copyItem(at: bundled, to: runtimeFile)
            UserDefaults.standard.set(Self.bundledRuntimeVersion, forKey: "quickgrade.runtime.version")
        } catch {
            showMessage(title: "QuickGrade could not start", message: error.localizedDescription)
        }
    }

    private func createWebView() {
        webView?.configuration.userContentController.removeScriptMessageHandler(forName: "quickGrade")
        webView?.removeFromSuperview()

        let content = WKUserContentController()
        content.add(self, name: "quickGrade")
        let shell = Self.shellVersion.jsQuoted
        let runtime = runtimeVersion.jsQuoted
        let bridge = """
        window.IOSBridge = {
          checkForUpdates: function(){ window.webkit.messageHandlers.quickGrade.postMessage({action:'checkForUpdates'}); },
          saveDataUrl: function(dataUrl,fileName,mimeType){ window.webkit.messageHandlers.quickGrade.postMessage({action:'saveDataUrl',dataUrl:dataUrl,fileName:fileName,mimeType:mimeType}); },
          getShellVersion: function(){ return \(shell); },
          getRuntimeVersion: function(){ return \(runtime); }
        };
        """
        content.addUserScript(WKUserScript(source: bridge, injectionTime: .atDocumentStart, forMainFrameOnly: true))

        let configuration = WKWebViewConfiguration()
        configuration.userContentController = content
        configuration.websiteDataStore = .default()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []

        let webView = WKWebView(frame: .zero, configuration: configuration)
        webView.translatesAutoresizingMaskIntoConstraints = false
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.scrollView.contentInsetAdjustmentBehavior = .never
        webView.isOpaque = true
        webView.backgroundColor = .black
        view.addSubview(webView)
        NSLayoutConstraint.activate([
            webView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            webView.topAnchor.constraint(equalTo: view.topAnchor),
            webView.bottomAnchor.constraint(equalTo: view.bottomAnchor)
        ])
        self.webView = webView
    }

    private func loadRuntime() {
        guard FileManager.default.fileExists(atPath: runtimeFile.path) else { return }
        webView.loadFileURL(runtimeFile, allowingReadAccessTo: runtimeDirectory)
    }

    func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
        guard message.name == "quickGrade",
              let body = message.body as? [String: Any],
              let action = body["action"] as? String else { return }
        switch action {
        case "checkForUpdates":
            checkForUpdates(userInitiated: true)
        case "saveDataUrl":
            guard let dataURL = body["dataUrl"] as? String,
                  let fileName = body["fileName"] as? String else { return }
            saveDataURL(dataURL, fileName: fileName)
        default:
            break
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        checkForUpdates(userInitiated: false)
    }

    @available(iOS 15.0, *)
    func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping (WKPermissionDecision) -> Void
    ) {
        decisionHandler(frame.isMainFrame ? .grant : .deny)
    }

    private func saveDataURL(_ dataURL: String, fileName: String) {
        do {
            guard let comma = dataURL.firstIndex(of: ",") else {
                throw RuntimeError("The exported file is invalid.")
            }
            let encoded = String(dataURL[dataURL.index(after: comma)...])
            guard let data = Data(base64Encoded: encoded, options: .ignoreUnknownCharacters) else {
                throw RuntimeError("The exported file could not be decoded.")
            }
            let invalid = CharacterSet(charactersIn: "\\/:*?\"<>|")
            let sanitized = fileName.components(separatedBy: invalid).joined(separator: "_")
            let safeName = sanitized.isEmpty ? "QuickGrade_Answer_Sheet.png" : sanitized
            let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent(safeName)
            try data.write(to: fileURL, options: .atomic)

            let activity = UIActivityViewController(activityItems: [fileURL], applicationActivities: nil)
            activity.popoverPresentationController?.sourceView = view
            activity.popoverPresentationController?.sourceRect = CGRect(
                x: view.bounds.midX,
                y: view.bounds.midY,
                width: 1,
                height: 1
            )
            activity.completionWithItemsHandler = { _, _, _, _ in
                try? FileManager.default.removeItem(at: fileURL)
            }
            present(activity, animated: true)
        } catch {
            showMessage(title: "Could not save answer sheet", message: error.localizedDescription)
        }
    }

    private func checkForUpdates(userInitiated: Bool) {
        if userInitiated { updateStatus("Checking for update…") }
        URLSession.shared.dataTask(with: Self.manifestURL) { [weak self] data, _, error in
            guard let self else { return }
            do {
                if let error { throw error }
                guard let data,
                      let manifest = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      let latest = manifest["version"] as? String,
                      let sha = manifest["sha256"] as? String,
                      let urlText = manifest["download_url"] as? String,
                      let packageURL = URL(string: urlText),
                      packageURL.scheme == "https" else {
                    throw RuntimeError("The update manifest is invalid.")
                }
                let minimumShell = manifest["min_shell_version"] as? String ?? "0"
                guard Self.compare(Self.shellVersion, minimumShell) != .orderedAscending else {
                    DispatchQueue.main.async {
                        self.showMessage(title: "New iOS shell required", message: "This update needs a newer QuickGrade IPA. Your saved grades remain on this iPhone.")
                    }
                    return
                }
                guard Self.compare(latest, self.runtimeVersion) == .orderedDescending else {
                    if userInitiated { self.updateStatus("QuickGrade is up to date.") }
                    return
                }
                let notes = manifest["notes"] as? String ?? "A QuickGrade update is available."
                DispatchQueue.main.async {
                    let alert = UIAlertController(title: "QuickGrade update \(latest)", message: notes + "\n\nYour quizzes, roster, scores, scan history, and saved images are kept.", preferredStyle: .alert)
                    alert.addAction(UIAlertAction(title: "Not now", style: .cancel))
                    alert.addAction(UIAlertAction(title: "Download & Install", style: .default) { _ in
                        self.installUpdate(from: packageURL, version: latest, expectedSHA256: sha)
                    })
                    self.present(alert, animated: true)
                }
            } catch {
                if userInitiated {
                    self.updateStatus("Could not check update: \(error.localizedDescription)")
                }
            }
        }.resume()
    }

    private func installUpdate(from packageURL: URL, version: String, expectedSHA256: String) {
        updateStatus("Downloading update…")
        URLSession.shared.dataTask(with: packageURL) { [weak self] data, _, error in
            guard let self else { return }
            do {
                if let error { throw error }
                guard let data else { throw RuntimeError("The update download was empty.") }
                let actual = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
                guard actual.caseInsensitiveCompare(expectedSHA256) == .orderedSame else {
                    throw RuntimeError("Update verification failed.")
                }
                let html = try Self.extractStoredIndexHTML(from: data)
                guard let marker = "<html".data(using: .utf8), html.range(of: marker) != nil else {
                    throw RuntimeError("The update does not contain a valid QuickGrade page.")
                }
                let temporary = self.runtimeDirectory.appendingPathComponent("index.html.new")
                try html.write(to: temporary, options: .atomic)
                if FileManager.default.fileExists(atPath: self.runtimeFile.path) {
                    _ = try FileManager.default.replaceItemAt(self.runtimeFile, withItemAt: temporary)
                } else {
                    try FileManager.default.moveItem(at: temporary, to: self.runtimeFile)
                }
                UserDefaults.standard.set(version, forKey: "quickgrade.runtime.version")
                DispatchQueue.main.async {
                    self.createWebView()
                    self.loadRuntime()
                    self.showMessage(title: "QuickGrade updated", message: "Runtime \(version) is installed. Your saved data was preserved.")
                }
            } catch {
                self.updateStatus("Update failed: \(error.localizedDescription)")
                DispatchQueue.main.async {
                    self.showMessage(title: "Update failed", message: error.localizedDescription)
                }
            }
        }.resume()
    }

    // The permanent update ZIP uses one uncompressed index.html entry. The outer SHA-256
    // authenticates the complete package before this dependency-free extractor runs.
    private static func extractStoredIndexHTML(from zip: Data) throws -> Data {
        guard zip.count >= 30,
              zip.u32LE(0) == 0x04034b50 else { throw RuntimeError("Invalid update ZIP.") }
        let flags = zip.u16LE(6)
        let method = zip.u16LE(8)
        guard flags & 0x0001 == 0, flags & 0x0008 == 0, method == 0 else {
            throw RuntimeError("The update ZIP must contain an uncompressed index.html file.")
        }
        let size = Int(zip.u32LE(18))
        let nameLength = Int(zip.u16LE(26))
        let extraLength = Int(zip.u16LE(28))
        let nameStart = 30
        let contentStart = nameStart + nameLength + extraLength
        guard nameLength > 0, contentStart >= nameStart, size >= 0,
              contentStart + size <= zip.count,
              let name = String(data: zip[nameStart..<(nameStart + nameLength)], encoding: .utf8),
              name == "index.html" else { throw RuntimeError("index.html is missing from the update ZIP.") }
        return Data(zip[contentStart..<(contentStart + size)])
    }

    private static func compare(_ lhs: String, _ rhs: String) -> ComparisonResult {
        let a = lhs.split(separator: ".").map { Int($0) ?? 0 }
        let b = rhs.split(separator: ".").map { Int($0) ?? 0 }
        for index in 0..<max(a.count, b.count) {
            let av = index < a.count ? a[index] : 0
            let bv = index < b.count ? b[index] : 0
            if av < bv { return .orderedAscending }
            if av > bv { return .orderedDescending }
        }
        return .orderedSame
    }

    private func updateStatus(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            self?.webView?.evaluateJavaScript("window.quickGradeUpdateStatus && window.quickGradeUpdateStatus(\(message.jsQuoted));")
        }
    }

    private func showMessage(title: String, message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.presentedViewController == nil else { return }
            let alert = UIAlertController(title: title, message: message, preferredStyle: .alert)
            alert.addAction(UIAlertAction(title: "OK", style: .default))
            self.present(alert, animated: true)
        }
    }
}

private struct RuntimeError: LocalizedError {
    let message: String
    init(_ message: String) { self.message = message }
    var errorDescription: String? { message }
}

private extension Data {
    func u16LE(_ offset: Int) -> UInt16 {
        UInt16(self[offset]) | (UInt16(self[offset + 1]) << 8)
    }

    func u32LE(_ offset: Int) -> UInt32 {
        UInt32(self[offset]) |
        (UInt32(self[offset + 1]) << 8) |
        (UInt32(self[offset + 2]) << 16) |
        (UInt32(self[offset + 3]) << 24)
    }
}

private extension String {
    var jsQuoted: String {
        let data = try? JSONSerialization.data(withJSONObject: [self])
        let json = data.flatMap { String(data: $0, encoding: .utf8) } ?? "[\"\"]"
        return String(json.dropFirst().dropLast())
    }
}
