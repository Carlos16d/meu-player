package com.meuapp.player.engine;

import org.libtorrent4j.SessionManager;
import org.libtorrent4j.swig.*;

public class TorrentSession {
    
    public void applySettings(SessionManager session) {
        settings_pack sp = new settings_pack();
        
        sp.set_int(settings_pack.int_types.connections_limit.swigValue(), 50);
        sp.set_int(settings_pack.int_types.active_downloads.swigValue(), 3);
        sp.set_int(settings_pack.int_types.active_seeds.swigValue(), 5);
        sp.set_int(settings_pack.int_types.active_limit.swigValue(), 20);
        sp.set_int(settings_pack.int_types.request_timeout.swigValue(), 3);
        sp.set_int(settings_pack.int_types.peer_timeout.swigValue(), 30);
        sp.set_int(settings_pack.int_types.max_out_request_queue.swigValue(), 10000);
        
        sp.set_bool(settings_pack.bool_types.strict_end_game_mode.swigValue(), true);
        sp.set_bool(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true);
        sp.set_bool(settings_pack.bool_types.prioritize_partial_pieces.swigValue(), true);
        
        sp.set_int(settings_pack.int_types.download_rate_limit.swigValue(), 0);
        sp.set_int(settings_pack.int_types.upload_rate_limit.swigValue(), 0);
        
        session.swig().apply_settings(sp);
    }
}