import { useCallback, useEffect, useState } from "react";
import type {
  Attendance,
  Binding,
  Device,
  DeviceRegistrationToken,
  MicSegment,
  QueueEntry,
  Room,
} from "../../types";
import { localTime } from "../../utils";
import { Card, Stat, Table } from "../ui";
import type { ApiCall } from "./types";

const duration = (seconds: number) => {
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor((seconds % 3600) / 60);
  const rest = seconds % 60;
  return `${hours ? `${hours}时` : ""}${minutes}分${rest}秒`;
};

export function ControlPage({
  rooms,
  call,
  canManageDevices,
}: {
  rooms: Room[];
  call: ApiCall;
  canManageDevices: boolean;
}) {
  const [roomId, setRoomId] = useState("");
  const [devices, setDevices] = useState<Device[]>([]);
  const [segments, setSegments] = useState<MicSegment[]>([]);
  const [queue, setQueue] = useState<QueueEntry[]>([]);
  const [bindings, setBindings] = useState<Binding[]>([]);
  const [attendance, setAttendance] = useState<Attendance[]>([]);
  const [tokenRoomId, setTokenRoomId] = useState("");
  const [tokenRole, setTokenRole] = useState("robot");
  const [registrationToken, setRegistrationToken] = useState<DeviceRegistrationToken | null>(null);

  const load = useCallback(async () => {
    const suffix = roomId ? `?roomId=${roomId}` : "";
    const [segmentData, queueData, bindingData, attendanceData] = await Promise.all([
      call<MicSegment[]>(`/mic-segments${suffix}`),
      call<QueueEntry[]>(`/queue-entries${suffix}`),
      call<Binding[]>(`/bindings${suffix}`),
      call<Attendance[]>(`/attendance${suffix}`),
    ]);
    setSegments(segmentData);
    setQueue(queueData);
    setBindings(bindingData);
    setAttendance(attendanceData);
    if (canManageDevices) setDevices(await call<Device[]>("/devices"));
  }, [call, canManageDevices, roomId]);

  useEffect(() => { load().catch(() => undefined); }, [load]);

  const totalSeconds = segments
    .filter((item) => item.state === "closed")
    .reduce((sum, item) => sum + item.durationSeconds, 0);
  const uncertain = segments.filter((item) => item.state === "uncertain").length;
  const roomName = (id: string) => rooms.find((room) => room.id === id)?.name || id.slice(0, 8);
  const capabilityLabel = (device: Device) => device.role === "robot"
    ? `微信回复：${device.wechatGroupReplyEnabled ? "已启用" : "关闭"} / ${device.wechatCalibrationStatus}`
    : `映客采集：${device.ingkeeVoiceRoomCaptureEnabled ? "已启用" : "关闭"} / ${device.ingkeeCalibrationStatus}`;
  const createRegistrationToken = async () => {
    const created = await call<DeviceRegistrationToken>("/devices/registration-tokens", {
      method: "POST",
      body: JSON.stringify({
        roomId: tokenRoomId || roomId || rooms[0]?.id,
        role: tokenRole,
        ttlSeconds: 600,
      }),
    });
    setRegistrationToken(created);
    await load();
  };
  const updateCalibration = async (device: Device, enabled: boolean) => {
    const capability = device.role === "robot" ? "wechat_group_reply" : "ingkee_voice_room_capture";
    const summary = enabled
      ? "管理台确认真机包名、版本、页面特征、目标群/语音房节点和端到端验收通过"
      : "管理台关闭正式自动化能力";
    await call<Device>(`/devices/${device.id}/calibration`, {
      method: "POST",
      body: JSON.stringify({ capability, enabled, status: enabled ? "已校准" : "已关闭", summary }),
    });
    await load();
  };
  const disableDevice = async (device: Device) => {
    await call<Device>(`/devices/${device.id}/disable`, { method: "POST" });
    await load();
  };

  return <>
    <div className="stats-grid">
      <Stat label="已连接设备" value={String(devices.filter((item) => item.enabled).length)} detail="禁用设备不再接受签名事件" />
      <Stat label="核验麦时" value={duration(totalSeconds)} detail="只统计 closed 分段" />
      <Stat label="出勤成员" value={String(attendance.length)} detail="按班次和绑定身份汇总" />
      <Stat label="异常分段" value={String(uncertain)} detail="页面不可读时停止推算" />
    </div>
    <Card
      title="厅控筛选"
      action={<button onClick={() => load()}>刷新厅控数据</button>}
    >
      <label className="compact-input">厅房
        <select value={roomId} onChange={(event) => setRoomId(event.target.value)}>
          <option value="">全部厅房</option>
          {rooms.map((room) => <option value={room.id} key={room.id}>{room.name}</option>)}
        </select>
      </label>
    </Card>
    <div className="two-col">
      <Card title="设备状态">
        {canManageDevices && <div className="form-grid">
          <label>注册厅房
            <select value={tokenRoomId} onChange={(event) => setTokenRoomId(event.target.value)}>
              <option value="">使用当前/第一个厅房</option>
              {rooms.map((room) => <option value={room.id} key={room.id}>{room.name}</option>)}
            </select>
          </label>
          <label>设备角色
            <select value={tokenRole} onChange={(event) => setTokenRole(event.target.value)}>
              <option value="robot">微信机器人端</option>
              <option value="collector">映客采集端</option>
            </select>
          </label>
          <button onClick={() => createRegistrationToken()}>生成 10 分钟一次性令牌</button>
        </div>}
        {registrationToken && <p className="upload-zone">
          一次性注册令牌：<strong>{registrationToken.token}</strong><br />
          仅显示一次，{localTime(registrationToken.expiresAtEpochMs)} 过期。请在厅控 APK 输入中台 HTTPS 地址、设备名和该令牌。
        </p>}
        <Table headers={["设备", "厅房", "角色", "状态", "能力/校准", "最后在线", "操作"]} empty={!devices.length}>
          {devices.map((device) => <tr key={device.id}>
            <td>{device.name}</td>
            <td>{roomName(device.roomId)}</td>
            <td>{device.role === "robot" ? "微信机器人端" : "映客采集端"}</td>
            <td><span className="state">{device.enabled ? "启用" : "已禁用"}</span></td>
            <td>{capabilityLabel(device)}<small className="subline">{device.calibrationSummary || "正式能力默认关闭，真机校准后再启用"}</small></td>
            <td>{localTime(device.lastSeenAtEpochMs)}</td>
            <td className="actions">
              {canManageDevices && device.enabled && <>
                <button onClick={() => updateCalibration(device, true)}>启用能力</button>
                <button onClick={() => updateCalibration(device, false)}>关闭能力</button>
                <button onClick={() => disableDevice(device)}>禁用设备</button>
              </>}
            </td>
          </tr>)}
        </Table>
      </Card>
      <Card title="成员绑定">
        <Table headers={["微信昵称", "映客昵称", "厅房", "状态"]} empty={!bindings.length}>
          {bindings.map((binding) => <tr key={binding.id}>
            <td>{binding.wechatName}</td>
            <td>{binding.ingkeeName}</td>
            <td>{roomName(binding.roomId)}</td>
            <td><span className="state">{binding.state}</span></td>
          </tr>)}
        </Table>
      </Card>
    </div>
    <Card title="当前排麦">
      <Table headers={["厅房", "序号", "成员", "角色", "状态", "更新时间"]} empty={!queue.length}>
        {queue.map((entry) => <tr key={entry.id}>
          <td>{roomName(entry.roomId)}</td>
          <td>{entry.position}</td>
          <td>{entry.wechatName}</td>
          <td>{entry.role === "host" ? "主持" : "普通"}</td>
          <td><span className="state">{entry.state}</span></td>
          <td>{localTime(entry.updatedAtEpochMs)}</td>
        </tr>)}
      </Table>
    </Card>
    <Card title="麦时分段">
      <Table headers={["成员", "角色", "开始", "结束", "时长", "状态/原因"]} empty={!segments.length}>
        {segments.map((segment) => <tr key={segment.id}>
          <td>{segment.wechatName || segment.ingkeeName}<small className="subline">{segment.ingkeeName}</small></td>
          <td>{segment.role === "host" ? "主持" : "普通"}</td>
          <td>{localTime(segment.startedAtEpochMs)}</td>
          <td>{localTime(segment.endedAtEpochMs)}</td>
          <td>{duration(segment.durationSeconds)}</td>
          <td><span className="state">{segment.state}</span><small className="subline">{segment.correctionReason || "—"}</small></td>
        </tr>)}
      </Table>
    </Card>
  </>;
}
