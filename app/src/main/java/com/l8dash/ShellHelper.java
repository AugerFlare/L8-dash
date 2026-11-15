package com.l8dash;

import android.os.IBinder;
import android.os.Parcel;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Runs as the shell user (BYD's autoservice allows shell, denies normal apps). READ-ONLY:
 * only autoservice txn 5 (getInt) and 7 (getFloat) are ever sent. Only L8Dash's uid may connect.
 * Listens on 127.0.0.1:PORT. Start once per car boot from the PC: sh start-helper.sh
 * Protocol: "<txn> <device> <feature>\n" -> "<status> <value>\n"
 */
public class ShellHelper {
    static final int PORT = 47824; // CarProbe uses 47823; L8Dash is separate

    public static void main(String[] args) throws Exception {
        int allowedUid = Integer.parseInt(args[0]);
        IBinder svc = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "autoservice");
        ServerSocket server = new ServerSocket(PORT, 4, InetAddress.getByName("127.0.0.1"));
        System.out.println("l8dash helper up on " + PORT + ", uid " + allowedUid);
        while (true) {
            Socket s = server.accept();
            if (uidOf(s.getPort()) != allowedUid) { s.close(); continue; }
            new Thread(() -> serve(svc, s)).start();
        }
    }

    /** Client side (app process, off the main thread): one request -> {status, value} or throws. */
    static String[] query(int txn, int dev, int id) throws Exception {
        try (Socket s = new Socket("127.0.0.1", PORT);
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter out = new PrintWriter(s.getOutputStream(), true)) {
            out.println(txn + " " + dev + " " + id);
            String line = in.readLine();
            if (line == null) throw new Exception("helper closed");
            return line.split(" ");
        }
    }

    /** Owner uid of the client's end of the connection, from /proc/net/tcp(6). */
    static int uidOf(int clientPort) {
        String port = String.format(":%04X ", clientPort);
        for (String f : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try {
                for (String l : Files.readAllLines(Paths.get(f))) {
                    String[] c = l.trim().split("\\s+");
                    if (c.length > 7 && (c[1] + " ").endsWith(port)) return Integer.parseInt(c[7]);
                }
            } catch (Exception ignored) { }
        }
        return -1;
    }

    private static void serve(IBinder svc, Socket s) {
        try (Socket sock = s;
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));
             PrintWriter out = new PrintWriter(sock.getOutputStream(), true)) {
            for (String line; (line = in.readLine()) != null; ) {
                String[] p = line.trim().split(" ");
                if (p.length != 3 || !(p[0].equals("5") || p[0].equals("7"))) { out.println("-1 rejected"); continue; }
                int txn = Integer.parseInt(p[0]);
                Parcel d = Parcel.obtain(), r = Parcel.obtain();
                try {
                    d.writeInterfaceToken("android.gui.BYDAutoServer");
                    d.writeInt(Integer.parseInt(p[1]));
                    d.writeInt(Integer.parseInt(p[2]));
                    svc.transact(txn, d, r, 0);
                    int status = r.readInt();
                    String v = txn == 7 ? String.valueOf(r.readFloat()) : String.valueOf(r.readInt());
                    out.println(status + " " + v);
                } finally { d.recycle(); r.recycle(); }
            }
        } catch (Exception ignored) { }
    }
}
