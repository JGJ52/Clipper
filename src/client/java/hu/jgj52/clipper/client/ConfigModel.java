package hu.jgj52.clipper.client;

import io.wispforest.owo.config.annotation.Config;
import io.wispforest.owo.config.annotation.Modmenu;

@Modmenu(modId = "clipper")
@Config(name = "clipper", wrapperName = "ClipperConfig", defaultHook = true)
public class ConfigModel {
    public String obs_url = "ws://127.0.0.1:4455";
    public String obs_password = "";
    public Boolean obs_systemd = true;
    public String obs_service = "obs";
    public String zipline_url = "https://zipline.your.domain";
    public String zipline_token = "";
    public String blur_path = "/path/to/blur";
    public String blur_config = "/path/to/blue/config";
}
