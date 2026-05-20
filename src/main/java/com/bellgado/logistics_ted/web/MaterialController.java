package com.bellgado.logistics_ted.web;

import com.bellgado.logistics_ted.domain.Material;
import com.bellgado.logistics_ted.repository.MaterialRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MaterialController {

    private final MaterialRepository materials;

    public MaterialController(MaterialRepository materials) {
        this.materials = materials;
    }

    @GetMapping("/materials")
    public List<Material> list() {
        return materials.findAllByOrderByIdAsc();
    }
}
