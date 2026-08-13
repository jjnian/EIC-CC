package com.tuiyan.backend.controller;

import com.tuiyan.backend.model.dto.ApiResult;
import com.tuiyan.backend.model.dto.SuccessCountResponse;
import com.tuiyan.backend.repository.ExperienceFolderRepository;
import com.tuiyan.backend.support.WorkspaceContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 经验库文件夹：工作空间内任意层级的归类（与数据源文件夹端点平行）。
 * <p>前端拉全量文件夹后自行拼成树；移动文件夹时服务端做环检测，删除时把内容上提到父级。
 */
@RestController
@RequestMapping("/api/experience-folders")
public class ExperienceFolderController {

    private final ExperienceFolderRepository repo;

    public ExperienceFolderController(ExperienceFolderRepository repo) {
        this.repo = repo;
    }

    /** 列出工作空间下全部文件夹（扁平，前端拼树）。 */
    @GetMapping
    public ApiResult<List<Map<String, Object>>> list(@RequestParam(required = false) String workspaceId) {
        String ws = (workspaceId != null && !workspaceId.isBlank()) ? workspaceId : WorkspaceContext.required();
        return ApiResult.ok(repo.listMaps(ws));
    }

    /** 新建文件夹：{name, parentId?}。parentId 省略/空 = 建在根。 */
    @PostMapping
    public ApiResult<Map<String, Object>> create(@RequestBody(required = false) Map<String, Object> body) {
        String name = str(body, "name");
        String parentId = str(body, "parentId");
        return ApiResult.ok(ExperienceFolderRepository.toMap(repo.create(name, parentId)));
    }

    /** 重命名 / 移动：{name?, parentId?}。请求体出现 parentId 字段即视为移动（null=移到根）。 */
    @PutMapping("/{id}")
    public ApiResult<SuccessCountResponse> update(@PathVariable String id,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        String name = (body != null && body.containsKey("name")) ? str(body, "name") : null;
        boolean moveParent = body != null && body.containsKey("parentId");
        String parentId = str(body, "parentId");
        boolean ok = repo.update(id, name, moveParent, parentId);
        return ApiResult.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    /** 删除文件夹：内容（子文件夹 + 经验）上提到父级，不丢数据。 */
    @DeleteMapping("/{id}")
    public ApiResult<SuccessCountResponse> delete(@PathVariable String id) {
        boolean ok = repo.delete(id);
        return ApiResult.ok(new SuccessCountResponse(ok, ok ? 1 : 0));
    }

    private static String str(Map<String, Object> body, String key) {
        if (body == null) return null;
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
