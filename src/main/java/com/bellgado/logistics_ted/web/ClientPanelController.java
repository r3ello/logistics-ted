package com.bellgado.logistics_ted.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class ClientPanelController {

    @GetMapping("/electric-box/{token}")
    public String electricBoxPage(@PathVariable String token) {
        return "forward:/electric-box.html";
    }
}
