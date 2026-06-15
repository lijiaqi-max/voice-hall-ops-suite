import { useCallback, useEffect, useState } from "react";
import type { Attendance, Binding, Device, MicSegment, QueueEntry, Room } from "../../types";
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
        <Table headers={["设备", "厅房", "状态", "最后在线"]} empty={!devices.length}>
          {devices.map((device) => <tr key={device.id}>
            <td>{device.name}</td>
            <td>{roomName(device.roomId)}</td>
            <td><span className="state">{device.enabled ? "启用" : "已禁用"}</span></td>
            <td>{localTime(device.lastSeenAtEpochMs)}</td>
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
