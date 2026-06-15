import { useState, type FormEvent } from "react";
import type { Room } from "../../types";
import { Card } from "../ui";
import type { ApiCall } from "./types";

export function RoomsPage({ rooms, call, reload, notify }: { rooms: Room[]; call: ApiCall; reload: () => Promise<void>; notify: (s: string) => void }) {
  const [name, setName] = useState("");
  const [externalRoomId, setExternalRoomId] = useState("");

  const submit = async (e: FormEvent) => {
    e.preventDefault();
    await call("/rooms", { method: "POST", body: JSON.stringify({ name, platform: "ingkee", externalRoomId: externalRoomId || null }) });
    setName(""); setExternalRoomId(""); await reload(); notify("厅房已创建");
  };

  return <div className="two-col wide-left">
    <Card title="厅房列表">
      <div className="room-grid">{rooms.map((room) => (
        <div className="room-card" key={room.id}>
          <div className="room-symbol">音</div>
          <div><b>{room.name}</b><small>映客 · {room.externalRoomId || "未绑定平台厅号"}</small></div>
          <span className="state">启用</span>
        </div>
      ))}</div>
    </Card>
    <Card title="新建厅房">
      <form className="form-stack" onSubmit={submit}>
        <label>厅房名称<input required value={name} onChange={(e) => setName(e.target.value)} placeholder="例如：星河一厅" /></label>
        <label>映客厅号<input value={externalRoomId} onChange={(e) => setExternalRoomId(e.target.value)} /></label>
        <button className="primary">保存厅房</button>
      </form>
    </Card>
  </div>;
}
