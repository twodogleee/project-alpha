package com._36knight.alpha.prototype;

import com._36knight.alpha.vo.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作台的固定原型模板目录；只返回可公开能力，不进行模型调用。@author Andersen */
@RestController
@RequestMapping("/api/alpha/workspace/prototype-templates")
public class PrototypeTemplateController {
    /** 能力与执行资格由原型应用服务统一判断。 */
    private final PrototypeService service;
    /** 注入模板能力查询，不在接口层访问文件系统。 */
    public PrototypeTemplateController(PrototypeService service){this.service=service;}
    /** 返回固定版本与当前可提交状态，GET 不创建业务记录。 */
    @GetMapping
    public ApiResponse<List<PrototypeContracts.Template>> list(){return ApiResponse.success(service.templates());}
}
