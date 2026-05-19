import { useEffect, useState } from "react";
import { Link } from "react-router-dom";

export function MeetingsPage() {
  // The BFF does not have a list-meetings endpoint in this milestone — meetings
  // are created and immediately deep-linked into the workstation wizard.
  // This page is a placeholder router landing.
  const [hint, setHint] = useState<string>("");
  useEffect(() => {
    setHint('从右上角「声纹录入」开始，或下方进入会议向导。');
  }, []);
  return (
    <div>
      <h1>会议工作站</h1>
      <p>{hint}</p>
      <p>
        <Link to="/meetings/new" className="button">新建会议</Link>
      </p>
    </div>
  );
}
