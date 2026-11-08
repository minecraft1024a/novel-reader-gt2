import 'dart:async';
import 'dart:convert';

import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';

const _channel = MethodChannel('novel_reader/direct_bluetooth');
const _events = EventChannel('novel_reader/direct_bluetooth_events');

void main() {
  runApp(const NovelReaderApp());
}

class NovelReaderApp extends StatelessWidget {
  const NovelReaderApp({super.key});

  @override
  Widget build(BuildContext context) {
    final scheme = ColorScheme.fromSeed(seedColor: Colors.deepPurple);
    return MaterialApp(
      title: '腕上小说',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(useMaterial3: true, colorScheme: scheme),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  PlatformFile? _selected;
  List<Map<String, dynamic>> _history = <Map<String, dynamic>>[];
  SharedPreferences? _prefs;
  StreamSubscription<dynamic>? _eventsSub;
  final _macController = TextEditingController();
  List<Map<String, String>> _devices = <Map<String, String>>[];
  String _status = '等待选择小说';
  double? _progress;
  bool _directBluetoothAvailable = false;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    _load();
    _eventsSub = _events.receiveBroadcastStream().listen((event) {
      if (!mounted || event is! Map) return;
      final type = event['type'];
      if (type == 'progress') {
        setState(() => _progress = ((event['value'] as num?)?.toDouble() ?? 0) / 100);
      }
    });
    _checkDirectBluetooth();
  }

  @override
  void dispose() {
    _eventsSub?.cancel();
    _macController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString('history');
    final saved = raw == null ? <Map<String, dynamic>>[] : (jsonDecode(raw) as List).cast<Map<String, dynamic>>();
    setState(() {
      _prefs = prefs;
      _history = saved;
      _macController.text = prefs.getString('gt2Mac') ?? '';
    });
  }

  Future<void> _checkDirectBluetooth() async {
    try {
      final available = await _channel.invokeMethod<bool>('isDirectBluetoothAvailable') ?? false;
      final devices = await _channel.invokeListMethod<Map<dynamic, dynamic>>('listBluetoothDevices') ?? <Map<dynamic, dynamic>>[];
      if (mounted) {
        setState(() {
          _directBluetoothAvailable = available;
          _devices = devices.map((item) => <String, String>{
            'name': '${item['name'] ?? 'GT2'}',
            'address': '${item['address'] ?? ''}',
          }).toList();
          if (_macController.text.isEmpty && _devices.isNotEmpty) _macController.text = _devices.first['address'] ?? '';
        });
      }
    } on PlatformException {
      if (mounted) setState(() => _directBluetoothAvailable = false);
    }
  }

  Future<void> _pick() async {
    final result = await FilePicker.platform.pickFiles(
      type: FileType.custom,
      allowedExtensions: <String>['txt', 'md', 'markdown'],
      withData: false,
    );
    if (result == null || result.files.isEmpty) return;
    setState(() {
      _selected = result.files.single;
      _status = '已选择 ${result.files.single.name}';
      _progress = null;
    });
  }

  Future<void> _send() async {
    final selected = _selected;
    if (selected == null || selected.path == null) {
      _message('请先选择 .txt 或 .md 文件');
      return;
    }
    final mac = _macController.text.trim();
    if (mac.isEmpty) {
      _message('请填写或选择已配对手表的蓝牙 MAC 地址');
      return;
    }
    setState(() {
      _busy = true;
      _status = '正在连接手表…';
      _progress = 0;
    });
    await _prefs?.setString('gt2Mac', mac);
    try {
      await _channel.invokeMethod<void>('sendFile', <String, dynamic>{
        'path': selected.path,
        'mac': mac,
      });
      final entry = <String, dynamic>{
        'name': selected.name,
        'path': selected.path,
        'size': selected.size,
        'sentAt': DateTime.now().toIso8601String(),
      };
      _history = <Map<String, dynamic>>[entry, ..._history.where((e) => e['path'] != selected.path)].take(10).toList();
      await _prefs?.setString('history', jsonEncode(_history));
      if (mounted) setState(() => _status = '发送完成');
    } on PlatformException catch (error) {
      if (mounted) setState(() => _status = '发送失败：${error.message ?? error.code}');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _message(String text) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));

  @override
  Widget build(BuildContext context) {
    final selected = _selected;
    return Scaffold(
      appBar: AppBar(title: const Text('腕上小说')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
        children: <Widget>[
          Card(
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                Text('传输到 GT2', style: Theme.of(context).textTheme.titleLarge),
                const SizedBox(height: 8),
                Text(_directBluetoothAvailable ? '蓝牙已打开，可使用 GT2 直连' : '请打开蓝牙，并先在系统设置中与 GT2 配对', style: Theme.of(context).textTheme.bodyMedium),
                const SizedBox(height: 12),
                FilledButton.icon(onPressed: _busy ? null : _pick, icon: const Icon(Icons.folder_open), label: const Text('选择小说文件')),
                if (selected != null) ...<Widget>[
                  const SizedBox(height: 12),
                  ListTile(contentPadding: EdgeInsets.zero, leading: const Icon(Icons.description_outlined), title: Text(selected.name), subtitle: Text(_formatBytes(selected.size)), trailing: IconButton(onPressed: _busy ? null : () => setState(() => _selected = null), icon: const Icon(Icons.close))),
                ],
                if (_progress != null) ...<Widget>[
                  const SizedBox(height: 4),
                  LinearProgressIndicator(value: _progress),
                ],
                const SizedBox(height: 8),
                Text(_status),
                const SizedBox(height: 12),
                FilledButton(onPressed: _busy || selected == null ? null : _send, child: Text(_busy ? '发送中…' : '发送到手表')),
              ]),
            ),
          ),
          const SizedBox(height: 12),
          Card(
            child: ExpansionTile(
              title: const Text('GT2 蓝牙连接'),
              subtitle: const Text('私有 RFCOMM 文件通道，不经过运动健康'),
              childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              children: <Widget>[
                if (_devices.isNotEmpty)
                  DropdownButtonFormField<String>(
                    value: _devices.any((item) => item['address'] == _macController.text) ? _macController.text : null,
                    decoration: const InputDecoration(labelText: '已配对的 GT2'),
                    items: _devices.map((item) => DropdownMenuItem<String>(value: item['address'], child: Text('${item['name']}  ${item['address']}'))).toList(),
                    onChanged: _busy ? null : (value) { if (value != null) setState(() => _macController.text = value); },
                  ),
                const SizedBox(height: 12),
                TextField(controller: _macController, decoration: const InputDecoration(labelText: 'GT2 蓝牙 MAC 地址', hintText: 'AA:BB:CC:DD:EE:FF'), onChanged: (_) => _prefs?.setString('gt2Mac', _macController.text)),
                const SizedBox(height: 12),
                const Text('先安装并启动手表端 HAP，在系统蓝牙中完成配对，再发送文件。当前使用 svc 0x28 的 file_type=7；该接收路由需要真机验收。'),
              ],
            ),
          ),
          if (_history.isNotEmpty) ...<Widget>[
            const SizedBox(height: 12),
            Card(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: <Widget>[
                const ListTile(title: Text('最近发送')),
                for (final item in _history) ListTile(dense: true, leading: const Icon(Icons.history), title: Text('${item['name']}'), subtitle: Text('${item['sentAt']}')),
              ]),
            ),
          ],
        ],
      ),
    );
  }

  String _formatBytes(int bytes) {
    if (bytes < 1024) return '$bytes B';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).toStringAsFixed(1)} KB';
    return '${(bytes / (1024 * 1024)).toStringAsFixed(1)} MB';
  }
}
