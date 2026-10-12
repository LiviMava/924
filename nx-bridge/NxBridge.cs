// Compile against this machine's NX 2512 managed assemblies. Run inside NX via File > Execute > NX Open.
using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using NXOpen;
using NXOpen.Features;
using NXOpen.UF;

public class NxBridge
{
    private static Form window;
    private static Timer timer;
    private static FileStream instanceLock;
    private static string root;
    private static string instanceId;
    private static CheckBox enabled;
    private static Label status;
    private static bool busy;
    private static readonly JavaScriptSerializer Json = new JavaScriptSerializer { MaxJsonLength = 65536 };

    public static void Main(string[] args)
    {
        if (window != null && !window.IsDisposed) { window.Activate(); return; }
        // DLL output is under <workspace>/.runtime/nx-bridge; keep all IPC files there.
        root = Path.GetDirectoryName(typeof(NxBridge).Assembly.Location);
        foreach (string dir in new[] { "inbox", "outbox", "receipts" }) Directory.CreateDirectory(Path.Combine(root, dir));
        try { instanceLock = new FileStream(Path.Combine(root, "instance.lock"), FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None); }
        catch (IOException) { MessageBox.Show("Another NX instance owns this bridge directory. Use a separate directory for each NX instance."); return; }
        instanceId = Guid.NewGuid().ToString();
        window = new Form { Text = "RAG NX Bridge · 2512", Width = 460, Height = 180, TopMost = false };
        enabled = new CheckBox { Text = "允许此 RAG 项目读取状态和执行已确认的操作", Left = 15, Top = 15, Width = 420, Checked = false };
        status = new Label { Text = "已暂停。启用后连接当前工作零件；仅支持毫米零件。", Left = 15, Top = 55, Width = 420, Height = 60 };
        window.Controls.Add(enabled); window.Controls.Add(status);
        timer = new Timer { Interval = 250 };
        timer.Tick += Tick;
        window.FormClosed += delegate {
            timer.Stop(); timer.Dispose(); instanceLock.Dispose();
            string heartbeat = Path.Combine(root, "heartbeat.json");
            if (File.Exists(heartbeat)) File.Delete(heartbeat);
        };
        timer.Start(); window.Show();
    }

    public static int GetUnloadOption(string dummy) { return (int)Session.LibraryUnloadOption.AtTermination; }

    // WinForms timer ticks on the NX UI thread. No worker thread is allowed to touch NXOpen.
    private static void Tick(object sender, EventArgs eventArgs)
    {
        if (busy) return;
        busy = true;
        try
        {
            if (!enabled.Checked)
            {
                string paused = Path.Combine(root, "heartbeat.json");
                if (File.Exists(paused)) File.Delete(paused);
                return;
            }
            AtomicWrite(Path.Combine(root, "heartbeat.json"), new Dictionary<string, object> {
                { "instanceId", instanceId }, { "backend", "NXOPEN" }, { "nxVersion", "2512" }
            });
            string file = Directory.GetFiles(Path.Combine(root, "inbox"), "*.json").OrderBy(File.GetCreationTimeUtc).FirstOrDefault();
            if (file != null) Process(file);
        }
        catch (Exception e) { status.Text = "Bridge error: " + e.GetType().Name + ". 暂停并检查 NX 日志。"; enabled.Checked = false; }
        finally { busy = false; }
    }

    private static void Process(string file)
    {
        if (new FileInfo(file).Length > 16384) { File.Move(file, file + ".rejected"); return; }
        var request = Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(file, Encoding.UTF8));
        string requestId = Uuid(request, "requestId"), commandId = Uuid(request, "commandId");
        string operation = Convert.ToString(request["operation"], CultureInfo.InvariantCulture);
        var arguments = (Dictionary<string, object>)request["arguments"];
        Dictionary<string, object> result;
        try
        {
            if (Convert.ToInt64(request["deadlineEpochMs"]) < EpochMs())
                result = Failure("EXPIRED", "Request expired before NX execution", commandId);
            else if (operation == "context") result = Context();
            else if (operation == "status") result = ReadReceipt(Uuid(arguments, "commandId"));
            else if (operation == "create_block") result = CreateBlock(arguments, commandId);
            else result = Failure("FAILED", "Operation is not allowed", commandId);
        }
        catch (Exception e)
        {
            result = operation == "create_block" && File.Exists(Path.Combine(root, "receipts", commandId + ".json"))
                ? ReadReceipt(commandId) : Failure("FAILED", "NX operation rejected: " + e.GetType().Name, commandId);
        }
        AtomicWrite(Path.Combine(root, "outbox", requestId + ".json"), result);
        File.Delete(file);
        status.Text = operation + " · " + (result.ContainsKey("status") ? result["status"] : "READ") + " · " + DateTime.Now.ToString("HH:mm:ss");
    }

    private static Dictionary<string, object> Context()
    {
        Part part = Session.GetSession().Parts.Work;
        var result = new Dictionary<string, object> { { "backend", "NXOPEN" }, { "instanceId", instanceId }, { "nxVersion", "2512" }, { "hasWorkPart", part != null } };
        if (part == null) { result["snapshot"] = instanceId + ":none"; return result; }
        string units = part.PartUnits == BasePart.Units.Millimeters ? "mm" : part.PartUnits.ToString();
        var fingerprint = new StringBuilder(instanceId).Append('|').Append(part.Tag).Append('|').Append(part.FullPath).Append('|').Append(units);
        int features = 0, bodies = 0;
        foreach (Feature feature in part.Features)
        {
            features++; fingerprint.Append('|').Append(feature.Tag).Append(':').Append(feature.FeatureType).Append(':').Append(feature.Name);
        }
        foreach (Expression expression in part.Expressions)
            fingerprint.Append('|').Append(expression.Tag).Append(':').Append(expression.Name).Append('=').Append(expression.RightHandSide);
        foreach (Body body in part.Bodies)
        {
            bodies++; var box = new double[6]; UFSession.GetUFSession().Modl.AskBoundingBox(body.Tag, box);
            fingerprint.Append('|').Append(body.Tag);
            foreach (double value in box) fingerprint.Append(':').Append(value.ToString("R", CultureInfo.InvariantCulture));
        }
        result["partName"] = part.Leaf; result["units"] = units; result["featureCount"] = features; result["bodyCount"] = bodies;
        // Avoid sharing full filesystem paths with cloud models.
        result["snapshot"] = Hash(fingerprint.ToString());
        return result;
    }

    private static Dictionary<string, object> CreateBlock(Dictionary<string, object> args, string commandId)
    {
        string receiptPath = Path.Combine(root, "receipts", commandId + ".json");
        string argumentsHash = Hash(string.Join("|", args.Keys.OrderBy(k => k).Select(k => k + "=" + Convert.ToString(args[k], CultureInfo.InvariantCulture)).ToArray()));
        // A receipt is persisted BEFORE any mutation. An interrupted IN_PROGRESS is UNKNOWN, never replayed.
        if (File.Exists(receiptPath))
        {
            var previous = ReadReceipt(commandId);
            if (!previous.ContainsKey("argumentsHash") || !argumentsHash.Equals(previous["argumentsHash"]))
                return Failure("FAILED", "commandId belongs to different arguments", commandId);
            return previous;
        }
        if (!args.Keys.OrderBy(k => k).SequenceEqual(new[] { "expectedSnapshot", "height", "instanceId", "length", "width", "x", "y", "z" }.OrderBy(k => k)))
            throw new ArgumentException("Unexpected arguments");
        var context = Context();
        if (!context.ContainsKey("units") || !"mm".Equals(context["units"]) || !instanceId.Equals(args["instanceId"]) || !context["snapshot"].Equals(args["expectedSnapshot"]))
            return Failure("FAILED", "Work part or model state changed; preview again", commandId);
        double x = Number(args, "x", -100000, 100000), y = Number(args, "y", -100000, 100000), z = Number(args, "z", -100000, 100000);
        double length = Number(args, "length", 0.000001, 10000), width = Number(args, "width", 0.000001, 10000), height = Number(args, "height", 0.000001, 10000);
        var started = Failure("IN_PROGRESS", "NX mutation started; do not replay", commandId);
        started["argumentsHash"] = argumentsHash;
        AtomicWrite(receiptPath, started);
        Session session = Session.GetSession();
        // NX Open requires redo to be enabled before the visible mark is created.
        session.EnableRedo(true);
        Session.UndoMarkId mark = session.SetUndoMark(Session.MarkVisibility.Visible, "RAG Create Block " + commandId);
        BlockFeatureBuilder builder = null;
        Dictionary<string, object> outcome;
        try
        {
            builder = session.Parts.Work.Features.CreateBlockFeatureBuilder(null);
            builder.Type = BlockFeatureBuilder.Types.OriginAndEdgeLengths;
            builder.BooleanOption.Type = NXOpen.GeometricUtilities.BooleanOperation.BooleanType.Create;
            builder.SetOrientation(new Vector3d(1, 0, 0), new Vector3d(0, 1, 0));
            builder.SetOriginAndLengths(new Point3d(x, y, z), length.ToString("R", CultureInfo.InvariantCulture), width.ToString("R", CultureInfo.InvariantCulture), height.ToString("R", CultureInfo.InvariantCulture));
            Feature feature = (Feature)builder.Commit();
            Body[] createdBodies = feature.GetBodies();
            if (createdBodies.Length != 1) throw new InvalidOperationException("Expected one standalone body");
            var bounds = new double[6]; UFSession.GetUFSession().Modl.AskBoundingBox(createdBodies[0].Tag, bounds);
            var sizes = new[] { bounds[3] - bounds[0], bounds[4] - bounds[1], bounds[5] - bounds[2] };
            var expected = new[] { x, y, z, x + length, y + width, z + height };
            for (int i = 0; i < 6; i++) if (Math.Abs(bounds[i] - expected[i]) > 0.00001) throw new InvalidOperationException("Created geometry failed dimensions/origin validation");
            outcome = new Dictionary<string, object> { { "status", "SUCCEEDED" }, { "backend", "NXOPEN" }, { "commandId", commandId }, { "featureName", feature.Name }, { "featureTag", feature.Tag.ToString() }, { "snapshot", Context()["snapshot"] }, { "actualBoundsMm", bounds }, { "actualSizeMm", sizes }, { "geometryVerified", true } };
            // Deliberately do not save the user's part. NX native Undo remains available.
        }
        catch (Exception e)
        {
            try { session.UndoToMark(mark, null); outcome = Failure("FAILED", "NX rolled back: " + e.GetType().Name, commandId); }
            catch { outcome = Failure("UNKNOWN", "Rollback failed; inspect NX before further operations", commandId); }
        }
        finally { if (builder != null) { try { builder.Destroy(); } catch { /* Receipt tracks commit/rollback, not builder cleanup. */ } } }
        // If this write fails after Commit, existing IN_PROGRESS prevents duplicate application.
        outcome["argumentsHash"] = argumentsHash;
        AtomicWrite(receiptPath, outcome);
        return outcome;
    }

    private static Dictionary<string, object> ReadReceipt(string id)
    {
        string path = Path.Combine(root, "receipts", id + ".json");
        if (!File.Exists(path)) return Failure("NOT_FOUND", "No execution receipt; do not automatically replay", id);
        var receipt = Json.Deserialize<Dictionary<string, object>>(File.ReadAllText(path, Encoding.UTF8));
        if ("IN_PROGRESS".Equals(receipt["status"])) { receipt["status"] = "UNKNOWN"; receipt["message"] = "Interrupted or executing; inspect NX and do not replay"; }
        return receipt;
    }
    private static Dictionary<string, object> Failure(string state, string message, string id)
    { return new Dictionary<string, object> { { "status", state }, { "message", message }, { "commandId", id }, { "backend", "NXOPEN" } }; }
    private static string Uuid(Dictionary<string, object> values, string key)
    { return Guid.Parse(Convert.ToString(values[key], CultureInfo.InvariantCulture)).ToString(); }
    private static double Number(Dictionary<string, object> values, string key, double min, double max)
    {
        double value = Convert.ToDouble(values[key], CultureInfo.InvariantCulture);
        if (double.IsNaN(value) || double.IsInfinity(value) || value < min || value > max) throw new ArgumentException("Numeric range");
        return value;
    }
    private static long EpochMs() { return (long)(DateTime.UtcNow - new DateTime(1970, 1, 1)).TotalMilliseconds; }
    private static string Hash(string text)
    { using (var sha = SHA256.Create()) return BitConverter.ToString(sha.ComputeHash(Encoding.UTF8.GetBytes(text))).Replace("-", "").ToLowerInvariant(); }
    private static void AtomicWrite(string path, object body)
    {
        string temporary = path + "." + Guid.NewGuid().ToString("N") + ".tmp";
        using (var file = new FileStream(temporary, FileMode.CreateNew, FileAccess.Write, FileShare.None))
        {
            byte[] bytes = new UTF8Encoding(false).GetBytes(Json.Serialize(body)); file.Write(bytes, 0, bytes.Length); file.Flush(true);
        }
        if (File.Exists(path)) File.Replace(temporary, path, null); else File.Move(temporary, path);
    }
}
