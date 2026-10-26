//! Optional pipe ownership for a backend launched through Android su.
//! A root child cannot reliably be killed by its unprivileged Java parent.
//! EOF on the parent's pipe also handles app death without PID reuse races.
use std::io::{self, Read};
use std::thread;

pub fn watch_parent_stdin() -> io::Result<thread::JoinHandle<()>> {
    thread::Builder::new().name("parent-pipe".into()).spawn(|| {
        let stdin = io::stdin();
        let mut input = stdin.lock();
        let mut buffer = [0u8; 64];
        loop {
            match input.read(&mut buffer) {
                Ok(0) => break,
                Ok(_) => {}
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(_) => break,
            }
        }
        // Exit even if a transfer is blocked on I/O. OS descriptor cleanup
        // releases HTTPS/mDNS; this is not a successful transfer completion.
        std::process::exit(0);
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::BufRead;
    use std::process::{Command, Stdio};
    use std::time::{Duration, Instant};

    #[test]
    fn child_pipe_owner() {
        if std::env::var_os("LUFTLIFT_PIPE_TEST_CHILD").is_none() { return; }
        watch_parent_stdin().unwrap();
        println!("PIPE_READY");
        loop { thread::sleep(Duration::from_millis(50)); }
    }

    #[test]
    fn backend_lives_while_pipe_open_and_exits_on_eof() {
        let mut child = Command::new(std::env::current_exe().unwrap())
            .args(["--exact", "lifecycle::tests::child_pipe_owner", "--nocapture"])
            .env("LUFTLIFT_PIPE_TEST_CHILD", "1")
            .stdin(Stdio::piped()).stdout(Stdio::piped()).spawn().unwrap();
        let (ready_tx, ready_rx) = std::sync::mpsc::channel();
        let stdout = child.stdout.take().unwrap();
        thread::spawn(move || {
            for line in io::BufReader::new(stdout).lines().map_while(Result::ok) {
                if line == "PIPE_READY" { let _ = ready_tx.send(()); break; }
            }
        });
        if ready_rx.recv_timeout(Duration::from_secs(3)).is_err() {
            let _ = child.kill(); let _ = child.wait(); panic!("child did not start");
        }
        assert!(child.try_wait().unwrap().is_none());
        drop(child.stdin.take());
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            if let Some(status) = child.try_wait().unwrap() {
                assert!(status.success()); break;
            }
            if Instant::now() > deadline {
                let _ = child.kill(); let _ = child.wait(); panic!("backend survived EOF");
            }
            thread::sleep(Duration::from_millis(10));
        }
    }
}
