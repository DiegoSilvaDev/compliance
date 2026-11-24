# Compliance Risk Assessment Microservice

Microsserviço responsável por orquestrar a análise automatizada de risco e conformidade (*Compliance*) de empresas no momento de seu cadastro, combinando consultas externas de **Sanções Internacionais (KYC/Sanctions)** e **Prevenção à Lavagem de Dinheiro (AML)** com políticas de decisão de domínio e resiliência contra falhas de rede.

## Sumário

* [Visão Geral e Arquitetura](#visão-geral-e-arquitetura)
* [Stack Tecnológica](#stack-tecnológica)
* [Estrutura do Projeto](#estrutura-do-projeto)
* [Regras de Negócio (`CompliancePolicy`)](#regras-de-negócio-compliancepolicy)
* [Integrações Externas e Resiliência](#integrações-externas-e-resiliência)
* [Como Executar o Projeto](#como-executar-o-projeto)
* [Exemplos de Uso da API](#exemplos-de-uso-da-api)

## Visão Geral e Arquitetura

O projeto foi estruturado seguindo os princípios da **Arquitetura Hexagonal (Ports & Adapters)** e **Domain-Driven Design (DDD)**, isolando as regras de negócio de conformidade das tecnologias de transporte HTTP, persistência e clientes externos.

### Fluxo de Execução

1. **Cadastro da Empresa (`POST /companies`):** Exposto automaticamente via **Spring Data REST** e persistido em memória utilizando **Spring Data KeyValue** (`@KeySpace("companies")`).
2. **Disparo Orientado a Evento (`@HandleAfterCreate`):** Assim que uma `CompanyEntity` é criada, o `CompanyEventHandler` intercepta o evento do ciclo de vida do repositório, converte a entidade para o agregado de domínio `Company` e aciona o caso de uso `AnalyzeCompanyRiskUseCase`.
3. **Orquestração de Triagem (`AnalyzeCompanyRiskUseCase`):**
    * Consulta listas restritivas via `SanctionClient` (`GET /sanctions/companies/{registrationNumber}`).
    * Consulta histórico e indicadores de lavagem de dinheiro via `AntiMoneyLaunderingClient` (`GET /aml/v1/screening/{registrationNumber}`).
    * Consolida ambas as respostas no objeto de domínio `ComplianceScreening`.
4. **Avaliação de Risco (`CompliancePolicy`):** Aplica as regras de conformidade sobre o `ComplianceScreening`, definindo o `status` (`APPROVED`, `MANUAL_REVIEW` ou `REJECTED`), a pontuação (`score`) e o nível de risco (`LOW`, `MEDIUM`, `HIGH` ou `CRITICAL`).
5. **Atualização do Agregado:** O `RiskAssessment` resultante é aplicado na entidade `Company` e salvo novamente no `CompanyRepository`.

## Stack Tecnológica

* **Linguagem:** Java 25
* **Framework:** Spring Boot `4.0.3`
* **Ecossistema Cloud & Resiliência:** Spring Cloud `2025.1.1`
    * **Spring Cloud OpenFeign:** Clientes HTTP declarativos para integração com provedores de Sanções e AML.
    * **Resilience4j (`spring-cloud-starter-circuitbreaker-resilience4j`):** Circuit Breaker e fallback para tolerância a falhas nas chamadas externas.
* **API & Persistência:**
    * **Spring Data REST:** Exposição automática de endpoints RESTful com HATEOAS.
    * **Spring Data KeyValue:** Repositório em memória baseado em Map (`@EnableMapRepositories`).
* **Observabilidade & Utilitários:** Spring Boot Actuator, Lombok e SLF4J.
* **Build Tool:** Gradle
* **Simulação e Testes de API:** Mockoon e Postman

## Estrutura do Projeto

```text
src/main/java/br/com/ia/compliance
├── application
│   └── AnalyzeCompanyRiskUseCase.java         # Orquestra triagem externa, avaliação e persistência
├── domain
│   ├── Company.java                           # Agregado raiz da empresa e avaliação de risco
│   ├── CompanyId.java                         # Value Object identificador (UUID)
│   ├── CompanyRepository.java                 # Porta de saída (Output Port) para persistência
│   ├── CompliancePolicy.java                  # Regras de negócio para classificação de risco
│   ├── ComplianceScreening.java               # Modelo consolidado de Sanções e Perfil AML
│   ├── RiskAssessment.java                    # Value Object com score, nível e status da análise
│   ├── RiskAssessmentStatus.java              # Enum: APPROVED, MANUAL_REVIEW, REJECTED
│   └── RiskLevel.java                         # Enum: LOW, MEDIUM, HIGH, CRITICAL
├── infrastructure
│   ├── persistence
│   │   ├── entity
│   │   │   └── CompanyEntity.java             # Entidade KeyValue (@KeySpace("companies"))
│   │   ├── event
│   │   │   └── CompanyEventHandler.java       # Listener @HandleAfterCreate para disparo da análise
│   │   └── repository
│   │       ├── CompanyEntityRepository.java   # Interface Spring Data KeyValue
│   │       └── InMemoryCompanyRepository.java # Implementação da porta CompanyRepository
│   └── rest
│       ├── client
│       │   ├── AntiMoneyLaunderingClient.java # Feign Client para serviço AML
│       │   └── SanctionClient.java            # Feign Client com Fallback para serviço de Sanções
│       └── dto
│           ├── AmlResult.java                 # DTO de resposta AML e conversor toDomain()
│           └── SanctionResult.java          # DTO de resposta de Sanções e conversor toDomain()
└── ComplianceApplication.java                 # Classe principal (@EnableMapRepositories, @EnableFeignClients)
```

## Regras de Negócio (`CompliancePolicy`)

A decisão final da análise de risco é calculada de forma determinística pela `CompliancePolicy` e pelo construtor de `RiskAssessment`:

### 1. Determinação do Status (`RiskAssessmentStatus`)

| Condição Avaliada | Status Resultante | Prioridade |
| :--- | :--- | :---: |
| Alguma correspondência em lista de sanções com `confidence > 0.8` | `REJECTED` | 1ª (Crítica) |
| Empresa possui exposição política (`isPepPreset == true`) | `MANUAL_REVIEW` | 2ª |
| Sem sanções críticas nem PEP, mas `amlScore > 70` | `MANUAL_REVIEW` | 3ª |
| Nenhuma das condições acima atendida | `APPROVED` | Padrão |

### 2. Determinação do Nível de Risco (`RiskLevel`)

| Regra | Nível de Risco |
| :--- | :--- |
| `status == REJECTED` | `CRITICAL` |
| `score > 70` | `HIGH` |
| `score > 30` (até `70`) | `MEDIUM` |
| `score <= 30` | `LOW` |

## Integrações Externas e Resiliência

Os clientes Feign são configurados via `application.properties` com logs detalhados (`logger-level=full`), headers de autenticação e Circuit Breaker habilitado (`spring.cloud.openfeign.circuitbreaker.enabled=true`):

| Cliente Feign | URL Base Padrão | Endpoint Consumido | Header Padrão | Fallback |
| :--- | :--- | :--- | :--- | :--- |
| `sanction-client` | `http://localhost:3001` | `GET /sanctions/companies/{registrationNumber}` | `x-api-key: kyc-secret-123` | Retorna lista vazia de sanções (`SanctionResult(List.of())`) |
| `aml-client` | `http://localhost:3002` | `GET /aml/v1/screening/{registrationNumber}` | `authorization: Bearer xyz123` | — |

## Como Executar o Projeto

### Pré-requisitos

* **JDK 25** instalado e configurado.
* **Mockoon** (ou servidores HTTP equivalentes) rodando nas portas `3001` e `3002` para responder aos endpoints de Sanções e AML.
* **Postman** para disparar requisições de cadastro e consulta nos endpoints da aplicação.

### 1. Configurar os Mocks (Mockoon)

* **Servidor de Sanções (`localhost:3001`):**
    * Rota: `GET /sanctions/companies/:registrationNumber`
    * Template de resposta (`200 OK` - `application/json`):

```handlebars
{
  "matches": [
    {
      "entity": "{{urlParam 'registrationNumber'}}",
      "list": "OFAC SDN List",
      "reason": "Financing of Prohibited Entities",
      "confidenceScore": 0.98
    }
  ]
}
```

* **Servidor AML (`localhost:3002`):**
    * Rota: `GET /aml/v1/screening/:registrationNumber`
    * Template dinâmico em Handlebars (`200 OK` - `application/json`) para simular cenários aleatórios de risco (`LOW`, `MEDIUM` e `HIGH`), flags AML e ocorrências de PEP:

```handlebars
{{!-- riskLevel --}}
{{setVar 'riskLevel' (oneOf (array 'LOW' 'MEDIUM' 'HIGH'))}}

{{!-- score --}}
{{#if (eq (getVar 'riskLevel') 'LOW')}}
  {{setVar 'score' (faker 'number.int' min=0 max=30)}}
{{else}}
  {{#if (eq (getVar 'riskLevel') 'MEDIUM')}}
    {{setVar 'score' (faker 'number.int' min=31 max=60)}}
  {{else}}
    {{setVar 'score' (faker 'number.int' min=61 max=100)}}
  {{/if}}
{{/if}}

{{!-- isPepUser --}}
{{#if (eq (getVar 'riskLevel') 'HIGH')}}
  {{setVar 'isPepUser' true}}
{{else}}
  {{#if (eq (getVar 'riskLevel') 'MEDIUM')}}
    {{setVar 'isPepUser' (boolean)}}
  {{else}}
    {{setVar 'isPepUser' false}}
  {{/if}}
{{/if}}

{{!-- AML flags base --}}
{{setVar 'amlFlags' (array
  'SANCTIONS_MATCH'
  'ADVERSE_MEDIA'
  'HIGH_RISK_COUNTRY'
  'SUSPICIOUS_ACTIVITY'
  'STRUCTURING'
  'HIGH_VALUE_EXPECTED'
)}}

{{#if (eq (getVar 'riskLevel') 'HIGH')}}
  {{setVar 'flagCount' (faker 'number.int' min=1 max=5)}}
{{else}}
  {{#if (eq (getVar 'riskLevel') 'MEDIUM')}}
    {{setVar 'flagCount' (faker 'number.int' min=1 max=3)}}
  {{else}}
    {{setVar 'flagCount' (faker 'number.int' min=0 max=1)}}
  {{/if}}
{{/if}}

{{setVar 'selectedFlags' (someOf (getVar 'amlFlags') (getVar 'flagCount') (getVar 'flagCount'))}}

{{#if (getVar 'isPepUser')}}
  {{setVar 'selectedFlags' (concat (getVar 'selectedFlags') (array 'PEP_ASSOCIATED'))}}
{{/if}}

{
  "riskLevel": "{{getVar 'riskLevel'}}",
  "riskScore": {{getVar 'score'}},
  "flags": {{{stringify (getVar 'selectedFlags')}}},
  "pep": {
    "isPep": {{getVar 'isPepUser'}},
    "occurrences": [
      {{#if (getVar 'isPepUser')}}
        {{#repeat (faker 'number.int' min=1 max=3)}}
        {
          "personName": "{{faker 'person.fullName'}}",
          "position": "{{faker 'person.jobTitle'}}"
        }{{#unless @last}},{{/unless}}
        {{/repeat}}
      {{/if}}
    ]
  }
}
```

### 2. Iniciar a Aplicação

Na raiz do projeto, execute via Gradle Wrapper:

```bash
./gradlew bootRun
```

*(No Windows, utilize `gradlew.bat bootRun`).*

A aplicação iniciará na porta `8080` com o Actuator exposto em `/actuator`.

## Exemplos de Uso da API

Os endpoints abaixo podem ser testados diretamente via **Postman**:

### Cadastrar uma Empresa e Acionar a Análise de Risco

* **Método:** `POST`
* **URL:** `http://localhost:8080/companies`
* **Headers:** `Content-Type: application/json`
* **Body (`raw` / `JSON`):**

```json
{
  "name": "Logistics",
  "registrationNumber": "REG-1234"
}
```

### Consultar a Empresa com o Resultado do Compliance

* **Método:** `GET`
* **URL:** `http://localhost:8080/companies/d6cffc0d-c526-467b-b901-6ae31079f8ed`

**Resposta (`200 OK`):**

```json
{
  "name": "Logistics",
  "registrationNumber": "REG-1234",
  "riskAssessment": {
    "score": 100,
    "level": "CRITICAL",
    "status": "REJECTED"
  },
  "_links": {
    "self": {
      "href": "http://localhost:8080/companies/d6cffc0d-c526-467b-b901-6ae31079f8ed"
    },
    "companyEntity": {
      "href": "http://localhost:8080/companies/d6cffc0d-c526-467b-b901-6ae31079f8ed"
    }
  }
}
```